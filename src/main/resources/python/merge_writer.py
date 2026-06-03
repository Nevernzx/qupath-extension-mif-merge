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

    libvips_ver = pyvips.version(0)
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

    # -------- Bandjoin --------
    images = [img for _, img in all_channels]
    if len(images) == 1:
        merged = images[0]
    else:
        merged = images[0].bandjoin(images[1:])
    emit(f"bandjoin complete: bands={merged.bands} format={merged.format}")

    # -------- tiffsave --------
    save_kwargs: dict = {
        "tile": True,
        "tile_width": out_cfg["tile_size"],
        "tile_height": out_cfg["tile_size"],
        "bigtiff": True,
        "compression": out_cfg["compression"],
    }
    if out_cfg["compression"] in ("jpeg", "jp2k", "webp"):
        save_kwargs["Q"] = int(out_cfg.get("quality", 85))
    if out_cfg["pyramid"] != "single":
        save_kwargs["pyramid"] = True
        # vips tiffsave does dyadic by default; sparse is not directly supported
    # Multi-channel uint16 → save as multi-page TIFF with OME-XML
    # Set 'subifd' so pyramid pages are sub-IFDs (cleaner for OME-TIFF readers).
    save_kwargs["subifd"] = True

    emit(f"writing {out_cfg['path']} (compression={out_cfg['compression']}, pyramid={out_cfg['pyramid']}, tile={out_cfg['tile_size']})")
    t0 = time.time()
    merged.tiffsave(out_cfg["path"], **save_kwargs)
    elapsed = time.time() - t0
    emit(f"tiffsave complete in {elapsed:.1f} s")

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
