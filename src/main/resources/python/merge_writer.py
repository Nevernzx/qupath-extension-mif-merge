#!/usr/bin/env python3
"""
pyvips-based merger for multi-channel qptiff WSIs.

Reads a JSON "recipe" file produced by the Java side that describes:
  - the fixed qptiff and which of its channels to keep
  - each moving qptiff with its DAPI->fixed affine matrix and which non-DAPI
    channels to keep
  - the output OME-TIFF path and compression / pyramid / tile-size options

For each kept channel we load one TIFF page via pyvips (lazy, native I/O),
optionally warp the moving channels by inverting the affine, bandjoin all
selected channels, and tiffsave with pyramid + compression. No intermediate
file is written — vips streams the pixels.

Expects PyVips and libvips to be available. Print progress lines to stdout
in the form "MERGE: <message>" so the Java side can surface them in the GUI.
"""
from __future__ import annotations

import json
import sys
import time
import traceback


def emit(msg: str) -> None:
    """Send a line back to the Java side. flush so it appears immediately."""
    print(f"MERGE: {msg}", flush=True)


def err(msg: str) -> None:
    print(f"MERGE-ERR: {msg}", file=sys.stderr, flush=True)


def _supports_compression(pyvips_mod, compression: str) -> bool:
    """Probe whether libvips' tiffsave supports a given compression by trying a 1x1 save.
       Some Windows builds of libvips (notably pyvips-binary) ship without JPEG2000 or WebP."""
    try:
        tiny = pyvips_mod.Image.black(1, 1)
        kwargs = {"compression": compression}
        if compression in ("jpeg", "jp2k", "webp"):
            kwargs["Q"] = 85
        tiny.tiffsave_buffer(**kwargs)
        return True
    except Exception:
        return False


def pick_fallback_compression(pyvips_mod, requested: str, vips_format: str):
    """Return (effective_compression, note). 'note' is empty if no fallback was needed."""
    LOSSY = {"jp2k", "webp"}
    if requested not in LOSSY:
        # The lossless compressions (lzw, deflate, none, zstd) are universally
        # supported. Don't probe them.
        return requested, ""
    if _supports_compression(pyvips_mod, requested):
        return requested, ""
    # Requested a lossy codec that's not in this libvips build → pick a fallback.
    # For 8-bit (uchar) data, JPEG works fine and is small/fast.
    # For 16-bit (ushort) data, fall back to LZW (lossless).
    if vips_format == "uchar" and _supports_compression(pyvips_mod, "jpeg"):
        return "jpeg", (f"libvips was built without '{requested}' support; "
                        f"using JPEG instead (data is 8-bit, visually equivalent)")
    return "lzw", (f"libvips was built without '{requested}' support and data is "
                   f"{vips_format} (not 8-bit); using LZW (lossless, ~2-3x larger file)")


def invert_affine(m: list[list[float]]) -> tuple[float, float, float, float, float, float]:
    """Return (a, b, c, d, odx, ody) for vips affine such that
       output(x, y) = input(a*x + b*y + odx, c*x + d*y + ody)
       maps the moving image into the fixed grid.

       Our input matrix M maps moving_full -> fixed_full,
       i.e. M @ [x_m, y_m, 1]^T = [x_f, y_f, 1]^T.
       vips affine wants the INVERSE direction: given an output
       pixel (x_f, y_f), where do we sample from in input?
       That's M_inv @ [x_f, y_f, 1]^T.
    """
    a, b, tx = m[0]
    c, d, ty = m[1]
    det = a * d - b * c
    if abs(det) < 1e-12:
        raise ValueError(f"Affine matrix is singular (det={det})")
    inv_det = 1.0 / det
    ai = d * inv_det
    bi = -b * inv_det
    ci = -c * inv_det
    di = a * inv_det
    odx = (b * ty - d * tx) * inv_det
    ody = (c * tx - a * ty) * inv_det
    return ai, bi, ci, di, odx, ody


def run(recipe: dict) -> int:
    try:
        import pyvips
    except ImportError as e:
        err(f"pyvips is not installed: {e}")
        err("Install with: pip install pyvips")
        return 2

    try:
        libvips_ver = f"{pyvips.version(0)}.{pyvips.version(1)}.{pyvips.version(2)}"
    except Exception:
        libvips_ver = "?"
    emit(f"pyvips {pyvips.__version__} | libvips {libvips_ver}")

    fixed_cfg = recipe["fixed"]
    movings_cfg = recipe["movings"]
    out_cfg = recipe["output"]

    # -------- Read fixed channels (full-res pages) --------
    fixed_path = fixed_cfg["path"]
    emit(f"loading fixed {fixed_path}")
    fixed_kept: list[tuple] = []   # list of (channel_dict, vips image)
    for ch in fixed_cfg["channels"]:
        if not ch["include"]:
            continue
        img = pyvips.Image.tiffload(fixed_path, page=ch["page"])
        fixed_kept.append((ch, img))
        emit(f"  fixed ch '{ch['name']}' page={ch['page']} -> {img.width}x{img.height} bands={img.bands} type={img.format}")

    if not fixed_kept:
        err("no fixed channels selected")
        return 3

    # Output canvas size = fixed full-res
    out_w = fixed_kept[0][1].width
    out_h = fixed_kept[0][1].height

    # -------- Read + warp moving channels --------
    all_channels: list[tuple] = list(fixed_kept)   # (ch_dict, vips image)
    for mv_idx, mv in enumerate(movings_cfg):
        mv_path = mv["path"]
        matrix = mv["matrix"]
        emit(f"loading moving {mv_idx + 1}/{len(movings_cfg)}: {mv_path}")
        a, b, c, d, odx, ody = invert_affine(matrix)
        emit(f"  affine inv: [{a:+.6e} {b:+.6e}; {c:+.6e} {d:+.6e}] + ({odx:+.2f}, {ody:+.2f})")
        for ch in mv["channels"]:
            if not ch["include"]:
                continue
            raw = pyvips.Image.tiffload(mv_path, page=ch["page"])
            # vips.affine maps output_pixel -> input_pixel via [a,b,c,d,odx,ody]
            # oarea defines the output canvas. We use the fixed image size.
            warped = raw.affine(
                [a, b, c, d],
                odx=odx,
                ody=ody,
                oarea=[0, 0, out_w, out_h],
                interpolate=pyvips.Interpolate.new("bilinear"),
                background=[0],
                premultiplied=False,
            )
            all_channels.append((ch, warped))
            emit(f"  moving ch '{ch['name']}' page={ch['page']} warped -> {warped.width}x{warped.height}")

    emit(f"merged layout has {len(all_channels)} channels, output canvas {out_w}x{out_h}")

    # -------- Stack channels as TIFF pages (NOT bands) --------
    # TIFF + JPEG only supports samples-per-pixel in {1, 3, 4}. With N>4
    # channels we can't use bandjoin (which would produce an N-sample image).
    # Canonical OME-TIFF multi-channel layout is one channel per IFD/page,
    # which we get by arrayjoin (vertical stack) + page_height. Each page
    # ends up as a single-band image, which JPEG can compress just fine.
    images = [img for _, img in all_channels]
    if len(images) == 1:
        combined = images[0]
        page_height = out_h
    else:
        combined = pyvips.Image.arrayjoin(images, across=1)
        page_height = out_h
    emit(f"arrayjoin complete: total {combined.width}x{combined.height} "
         f"(= {len(images)} channels x page_height={page_height}), "
         f"per-page bands={images[0].bands} format={combined.format}")

    # -------- compression capability check + fallback --------
    requested_compression = out_cfg["compression"]
    effective_compression, fallback_note = pick_fallback_compression(
        pyvips, requested_compression, combined.format)
    if fallback_note:
        emit(f"NOTE: {fallback_note}")

    # -------- tiffsave --------
    save_kwargs: dict = {
        "tile": True,
        "tile_width": out_cfg["tile_size"],
        "tile_height": out_cfg["tile_size"],
        "bigtiff": True,
        "compression": effective_compression,
        # page_height splits the tall arrayjoin image into N pages
        "page_height": page_height,
    }
    if effective_compression in ("jpeg", "jp2k", "webp"):
        save_kwargs["Q"] = int(out_cfg.get("quality", 85))
    if out_cfg["pyramid"] != "single":
        save_kwargs["pyramid"] = True
        # Put pyramid levels as SubIFDs of the main IFDs — cleaner for OME-TIFF
        # readers (Bio-Formats / QuPath / ImageJ) than interleaving as more pages.
        save_kwargs["subifd"] = True

    emit(f"writing {out_cfg['path']} (compression={effective_compression}, "
         f"pyramid={out_cfg['pyramid']}, tile={out_cfg['tile_size']}, "
         f"page_height={page_height})")
    t0 = time.time()
    combined.tiffsave(out_cfg["path"], **save_kwargs)
    elapsed = time.time() - t0

    # Verify the file actually got written (vips occasionally swallows errors)
    import os
    if not os.path.exists(out_cfg["path"]):
        err(f"tiffsave returned success but {out_cfg['path']} doesn't exist")
        return 5
    size = os.path.getsize(out_cfg["path"])
    if size < 1024:
        err(f"tiffsave returned success but {out_cfg['path']} is only {size} bytes")
        return 6
    emit(f"tiffsave complete in {elapsed:.1f} s — output is {size / 1024 / 1024:.1f} MB")

    return 0


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        err("Usage: merge_writer.py <recipe.json>")
        return 1
    with open(argv[1], encoding="utf-8") as f:
        recipe = json.load(f)
    emit(f"recipe loaded from {argv[1]}")
    try:
        return run(recipe)
    except Exception as e:
        err(f"unhandled exception: {e}")
        traceback.print_exc(file=sys.stderr)
        return 4


if __name__ == "__main__":
    sys.exit(main(sys.argv))
