package qupath.ext.mifmerge.gui;

import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.mifmerge.core.MatrixRescaler;
import qupath.ext.mifmerge.qc.QcVisualizer;

import java.awt.Toolkit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import qupath.ext.mifmerge.core.MergedChannelLayout;
import qupath.ext.mifmerge.core.MifImageSource;
import qupath.ext.mifmerge.core.RegistrationOrchestrator;
import qupath.ext.mifmerge.io.BioFormatsMifSource;
import qupath.ext.mifmerge.merge.MergedServerFactory;
import qupath.ext.mifmerge.merge.OmeTiffMergeWriter;
import qupath.ext.mifmerge.merge.PyVipsWriter;
import qupath.lib.images.writers.ome.OMEPyramidWriter;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerProvider;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code Extensions > MIF Merge > Run merge…} command.
 *
 * <p>Minimum-viable UI: a dialog where the user picks multiple qptiff files
 * (first one becomes the fixed reference), sets the output path, and starts
 * the registration + merge. Progress is shown in a log text area; the actual
 * pipeline runs on a background thread so the UI stays responsive.
 *
 * <p>The dialog is intentionally simple — it can grow later (drag-and-drop,
 * re-ordering, per-pair affine inspection, etc.) but the goal here is to
 * provide an executable entry point that exercises the full pipeline.
 */
public final class MifMergeCommand implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(MifMergeCommand.class);

    private final QuPathGUI qupath;

    public MifMergeCommand(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    @Override
    public void run() {
        // Wrap everything in try/catch so any unexpected error surfaces in the
        // QuPath log instead of silently freezing the FX thread.
        try {
            Window owner = qupath != null && qupath.getStage() != null ? qupath.getStage() : null;
            showDialog(owner);
        } catch (Throwable t) {
            logger.error("MIF Merge: failed to open dialog", t);
            try {
                showError(null, "MIF Merge failed to open: " + t);
            } catch (Throwable ignored) {}
        }
    }

    private void showDialog(Window owner) {
        // Use a plain Stage (non-modal, non-blocking) instead of Dialog.showAndWait()
        // — showAndWait blocks the FX thread, and a modal Dialog whose window ends up
        // hidden behind the main QuPath window or off-screen will look like a freeze.
        Stage stage = new Stage();
        stage.setTitle("MIF Merge — register and combine multi-channel qptiffs");
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.initModality(Modality.NONE);   // explicit: main window stays interactive

        // --- File list ---
        ObservableList<File> files = FXCollections.observableArrayList();
        ListView<File> fileList = new ListView<>(files);
        fileList.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(File item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    boolean isFixed = getIndex() == 0;
                    setText((isFixed ? "[FIXED] " : "[moving] ") + item.getName());
                }
            }
        });
        fileList.setPrefHeight(160);

        Button addBtn = new Button("Add qptiff…");
        Button removeBtn = new Button("Remove selected");
        Button upBtn = new Button("Move up");
        Button downBtn = new Button("Move down");

        addBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Choose qptiff files (Ctrl/Cmd-click to select multiple)");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                    "qptiff / tif / ome.tif", "*.qptiff", "*.tif", "*.tiff", "*.ome.tif"));
            List<File> chosen = fc.showOpenMultipleDialog(stage);
            if (chosen != null) files.addAll(chosen);
        });
        removeBtn.setOnAction(e -> {
            File sel = fileList.getSelectionModel().getSelectedItem();
            if (sel != null) files.remove(sel);
        });
        upBtn.setOnAction(e -> swap(files, fileList.getSelectionModel().getSelectedIndex(), -1));
        downBtn.setOnAction(e -> swap(files, fileList.getSelectionModel().getSelectedIndex(), +1));

        HBox fileButtons = new HBox(8, addBtn, removeBtn, upBtn, downBtn);

        // --- Output + parameters ---
        TextField outPathField = new TextField();
        outPathField.setPromptText("output .ome.tif path");
        Button browseOut = new Button("Browse…");
        browseOut.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Where to save the merged OME-TIFF");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("OME-TIFF", "*.ome.tif", "*.ome.tiff"));
            if (!files.isEmpty()) {
                File parent = files.get(0).getParentFile();
                if (parent != null) fc.setInitialDirectory(parent);
                fc.setInitialFileName(stem(files.get(0).getName()) + "-merged.ome.tif");
            }
            File chosen = fc.showSaveDialog(stage);
            if (chosen != null) outPathField.setText(chosen.getAbsolutePath());
        });
        HBox outRow = new HBox(8, outPathField, browseOut);
        HBox.setHgrow(outPathField, Priority.ALWAYS);

        TextField dapiField = new TextField("DAPI");
        CheckBox keepMovingDapi = new CheckBox("Keep moving images' DAPI channels too (default: drop them to avoid duplicates)");
        keepMovingDapi.setSelected(false);
        Spinner<Integer> stage1Long = new Spinner<>(1000, 20000, 4000, 500);
        Spinner<Integer> stage2Long = new Spinner<>(1000, 50000, 14000, 1000);
        // Cap on SIFT keypoints per image. Memory scales ~linearly with this.
        // Default 60000 needs ~1.5 GB heap on 14000-px images. Drop to 15000
        // for 6-GB Windows machines.
        Spinner<Integer> nFeaturesSpinner = new Spinner<>(2000, 100000, 60000, 5000);

        // Stage 3: windowed full-resolution refinement (bounded memory)
        CheckBox stage3Enable = new CheckBox("Enable Stage 3 (windowed full-res refinement, ~1 px precision)");
        stage3Enable.setSelected(false);
        Spinner<Integer> stage3NumWindows = new Spinner<>(4, 64, 16, 2);
        Spinner<Integer> stage3WindowSize = new Spinner<>(512, 8192, 2048, 256);

        // OME-TIFF writer options
        ChoiceBox<String> compressionChoice = new ChoiceBox<>();
        compressionChoice.getItems().addAll(
                "Uncompressed (fastest, ~2x file size)",
                "LZW (lossless, default)",
                "ZLIB (lossless, alternative)",
                "J2K_LOSSY (smallest file, minor quality loss)");
        compressionChoice.setValue("LZW (lossless, default)");
        Spinner<Integer> tileSizeSpinner = new Spinner<>(256, 4096, 1024, 256);
        // Concurrent writer threads — each thread holds tile buffers for
        // fixed + all moving channels, so this is the main lever for write-
        // phase memory usage. Default 2 keeps memory bounded; raise to 4 for
        // ~30% faster write on a machine with plenty of RAM.
        Spinner<Integer> writeThreadsSpinner = new Spinner<>(1, 16, 2, 1);

        // Pyramid mode — dyadic is the safe default but lowest levels are rarely
        // accessed; sparse or single can cut write time by 20-40%.
        ChoiceBox<String> pyramidChoice = new ChoiceBox<>();
        pyramidChoice.getItems().addAll(
                "Dyadic (1, 2, 4, 8, ...) — smooth QuPath zoom",
                "Sparse (1, 4, 16) — faster write, OK zoom",
                "Single level — fastest, no QuPath zoom support");
        pyramidChoice.setValue("Dyadic (1, 2, 4, 8, ...) — smooth QuPath zoom");

        // Writer backend — pyvips is fastest (no intermediate file), libvips
        // is the two-step version, Bio-Formats is the all-Java fallback.
        ChoiceBox<String> backendChoice = new ChoiceBox<>();
        backendChoice.getItems().addAll(
                "pyvips (fastest, requires Python+pyvips installed)",
                "libvips (faster, requires `vips` on PATH, uses ~50 GB temp space)",
                "Bio-Formats (fallback, slowest, no install required)");
        backendChoice.setValue("pyvips (fastest, requires Python+pyvips installed)");

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        grid.setPadding(new Insets(8, 0, 8, 0));
        grid.addRow(0, new Label("Output:"), outRow);
        grid.addRow(1, new Label("DAPI channel match:"), dapiField);
        grid.add(keepMovingDapi, 0, 2, 2, 1);
        grid.addRow(3, new Label("Stage 1 long side (px):"), stage1Long);
        grid.addRow(4, new Label("Stage 2 long side (px):"), stage2Long);
        grid.addRow(5, new Label("SIFT keypoints per image:"), nFeaturesSpinner);
        grid.add(stage3Enable, 0, 6, 2, 1);
        grid.addRow(7, new Label("  Stage 3 windows:"), stage3NumWindows);
        grid.addRow(8, new Label("  Stage 3 window size (px):"), stage3WindowSize);
        grid.addRow(9, new Label("OME-TIFF compression:"), compressionChoice);
        grid.addRow(10, new Label("OME-TIFF tile size (px):"), tileSizeSpinner);
        grid.addRow(11, new Label("OME-TIFF write threads:"), writeThreadsSpinner);
        grid.addRow(12, new Label("OME-TIFF pyramid:"), pyramidChoice);
        grid.addRow(13, new Label("OME-TIFF writer backend:"), backendChoice);
        GridPane.setHgrow(outRow, Priority.ALWAYS);

        // --- Progress + log ---
        TextArea log = new TextArea();
        log.setEditable(false);
        log.setPrefRowCount(10);
        ProgressBar progress = new ProgressBar(0);
        progress.setPrefWidth(Double.MAX_VALUE);
        Label statusLabel = new Label("Idle. Add qptiff files, set output path, then Run merge.");
        statusLabel.setStyle("-fx-font-weight: bold;");
        HBox progressRow = new HBox(8, statusLabel);
        progressRow.setAlignment(Pos.CENTER_LEFT);

        // --- Bottom row: Run + Cancel + Close ---
        Button runBtn = new Button("Run merge");
        Button cancelBtn = new Button("Cancel & clean up");
        cancelBtn.setDisable(true);   // only enabled while a task is running
        Button closeBtn = new Button("Close");
        HBox bottomRow = new HBox(8, runBtn, cancelBtn, closeBtn);

        VBox content = new VBox(8,
                new Label("Select qptiffs (first is the fixed reference):"),
                fileList,
                fileButtons,
                grid,
                new Label("Progress:"),
                progressRow,
                progress,
                log,
                bottomRow);
        content.setPadding(new Insets(10));
        // Only the log should grow/shrink when the dialog is resized — keep
        // everything else (especially the progress bar) at its natural size
        // so the progress bar can't be pushed off-screen.
        VBox.setVgrow(log, Priority.ALWAYS);
        progress.setMinHeight(18);   // a bit taller than default for visibility

        // Need enough height to show: file list (160) + file buttons + 10-row param grid
        // (~280) + progress + log (200) + buttons. Was 600 and the progress bar fell off
        // the bottom of the window on some setups.
        Scene scene = new Scene(content, 740, 960);
        stage.setScene(scene);
        stage.setMinWidth(680);
        stage.setMinHeight(800);

        SimpleObjectProperty<Task<Void>> currentTask = new SimpleObjectProperty<>();
        // Shared between Cancel button and the running task so the cancel can
        // (a) kill the live libvips subprocess if any,
        // (b) know which files to delete when the task actually stops.
        AtomicReference<Process> currentProcess = new AtomicReference<>();
        AtomicReference<String> currentOutputPath = new AtomicReference<>();
        AtomicReference<Path> currentIntermediatePath = new AtomicReference<>();
        AtomicReference<Boolean> userCancelled = new AtomicReference<>(false);

        runBtn.setOnAction(evt -> {
            if (files.size() < 2) {
                showError(stage, "Pick at least 2 qptiff files (first = fixed reference).");
                return;
            }
            String outPath = outPathField.getText();
            if (outPath == null || outPath.isBlank()) {
                showError(stage, "Set an output path.");
                return;
            }
            runBtn.setDisable(true);
            cancelBtn.setDisable(false);
            log.clear();
            currentProcess.set(null);
            currentOutputPath.set(outPath);
            currentIntermediatePath.set(null);
            userCancelled.set(false);
            long taskStart = System.currentTimeMillis();

            Task<Void> task = makeTask(
                    new ArrayList<>(files), outPath,
                    dapiField.getText(),
                    keepMovingDapi.isSelected(),
                    stage1Long.getValue(), stage2Long.getValue(),
                    nFeaturesSpinner.getValue(),
                    stage3Enable.isSelected(),
                    stage3NumWindows.getValue(),
                    stage3WindowSize.getValue(),
                    compressionFromChoice(compressionChoice.getValue()),
                    tileSizeSpinner.getValue(),
                    writeThreadsSpinner.getValue(),
                    pyramidModeFromChoice(pyramidChoice.getValue()),
                    backendFromChoice(backendChoice.getValue()),
                    log,
                    currentProcess, currentIntermediatePath);
            // Bind the progress bar + status label to the Task's progress/message.
            // Task#updateProgress and #updateMessage (called from the worker thread)
            // are thread-safe and update these properties on the FX thread.
            progress.progressProperty().bind(task.progressProperty());
            statusLabel.textProperty().bind(task.messageProperty());

            task.setOnSucceeded(e -> Platform.runLater(() -> {
                progress.progressProperty().unbind();
                statusLabel.textProperty().unbind();
                progress.setProgress(1.0);
                long elapsed = (System.currentTimeMillis() - taskStart) / 1000;
                statusLabel.setText(String.format(" ✓ Merge complete in %ds", elapsed));
                statusLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #2a8000;");
                runBtn.setDisable(false);
                cancelBtn.setDisable(true);
                try { Toolkit.getDefaultToolkit().beep(); } catch (Throwable ignored) {}
                showSuccessAlert(stage, outPath, elapsed);
            }));
            task.setOnFailed(e -> Platform.runLater(() -> {
                progress.progressProperty().unbind();
                statusLabel.textProperty().unbind();
                progress.setProgress(0);
                statusLabel.setText(" ✗ Failed (cleaning up files…)");
                statusLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #c00000;");
                runBtn.setDisable(false);
                cancelBtn.setDisable(true);
                Throwable t = task.getException();
                logger.error("MIF Merge failed", t);
                // Cleanup on a daemon thread so we don't freeze the FX thread
                // while Files.deleteIfExists retries on Windows.
                runCleanupAsync(currentOutputPath.get(), currentIntermediatePath.get(), log,
                        () -> statusLabel.setText(" ✗ Failed"));
                try { Toolkit.getDefaultToolkit().beep(); } catch (Throwable ignored) {}
                showError(stage, "Failed: " + (t == null ? "(no exception)" : t.getMessage()));
            }));
            task.setOnCancelled(e -> Platform.runLater(() -> {
                progress.progressProperty().unbind();
                statusLabel.textProperty().unbind();
                progress.setProgress(0);
                statusLabel.setText(" — Cancelled (cleaning up files…)");
                statusLabel.setStyle("-fx-font-weight: bold;");
                runBtn.setDisable(false);
                cancelBtn.setDisable(true);
                runCleanupAsync(currentOutputPath.get(), currentIntermediatePath.get(), log,
                        () -> statusLabel.setText(" — Cancelled"));
            }));
            currentTask.set(task);
            Thread thr = new Thread(task, "mif-merge-task");
            thr.setDaemon(true);
            thr.start();
        });

        cancelBtn.setOnAction(evt -> {
            Task<Void> t = currentTask.get();
            if (t == null || !t.isRunning()) {
                return;
            }
            appendLog(log, "── User cancel requested ──");
            userCancelled.set(true);
            // 1. Kill the libvips subprocess if any. This unlocks files on
            //    Windows so we can delete them.
            Process p = currentProcess.getAndSet(null);
            if (p != null && p.isAlive()) {
                appendLog(log, "  killing vips subprocess (pid=" + p.pid() + ")");
                p.destroyForcibly();
            }
            // 2. Interrupt the JavaFX Task. Inside makeTask we check
            //    isCancelled() between steps; the Bio-Formats writer doesn't
            //    respond to interruption itself but the thread will stop at
            //    its next yield point.
            t.cancel(true);
            cancelBtn.setDisable(true);
            statusLabel.textProperty().unbind();
            statusLabel.setText(" — Cancelling, please wait…");
            statusLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #a05000;");
        });

        closeBtn.setOnAction(evt -> stage.close());

        stage.setOnCloseRequest(evt -> {
            Task<Void> t = currentTask.get();
            if (t != null && t.isRunning()) {
                // Same code path as the Cancel button
                Process p = currentProcess.getAndSet(null);
                if (p != null && p.isAlive()) {
                    p.destroyForcibly();
                }
                t.cancel(true);
                // setOnCancelled handler will trigger async cleanup
            }
        });

        stage.show();   // non-blocking
    }

    private Task<Void> makeTask(List<File> files, String outPath,
                                String dapiName, boolean keepMovingDapi,
                                int stage1Long, int stage2Long,
                                int nFeatures,
                                boolean enableStage3, int stage3NumWindows, int stage3WindowSize,
                                OMEPyramidWriter.CompressionType compression, int tileSize,
                                int nWriteThreads,
                                OmeTiffMergeWriter.PyramidMode pyramidMode,
                                OmeTiffMergeWriter.WriterBackend backend,
                                TextArea log,
                                AtomicReference<Process> processSink,
                                AtomicReference<Path> intermediatePathSink) {
        return new Task<>() {
            @Override
            protected Void call() throws Exception {
                // We run the pipeline in TWO sequential phases to keep peak memory low:
                //  Phase A: open only the lightweight BioFormatsMifSource readers, run
                //           SIFT registration on each pair, store the affine matrices,
                //           then CLOSE these readers.
                //  Phase B: open QuPath's heavier BioFormatsImageServer instances (each
                //           with an internal reader pool + tile cache), build the
                //           virtual merged server, write OME-TIFF, close.
                //
                // Previously both phases ran simultaneously (each file was opened twice
                // before SIFT even started), which on a multi-channel qptiff WSI can
                // cause Bio-Formats + QuPath tile caches to commit huge amounts of
                // memory in the first few seconds and crash low-memory systems.

                List<AffineTransform> movingAffines = new ArrayList<>();
                List<List<String>> channelNamesPerSource = new ArrayList<>();
                List<String> labels = new ArrayList<>();

                // Rough progress milestones:
                //   0.00 - 0.05: open Bio-Formats readers
                //   0.05 - 0.60: register (Stage 1+2 [+ optional Stage 3])
                //   0.60 - 0.65: open QuPath ImageServers
                //   0.65 - 0.95: write OME-TIFF
                //   0.95 - 1.00: cleanup
                updateProgress(0, 1);
                updateMessage("Opening Bio-Formats readers…");

                // -------- Phase A: registration --------
                appendLog(log, "Phase A: register (lightweight readers only)");
                logMem(log, "before opening Bio-Formats readers");
                List<MifImageSource> bf = new ArrayList<>();
                try {
                    for (int fi = 0; fi < files.size(); fi++) {
                        File f = files.get(fi);
                        appendLog(log, "  Opening " + f.getName());
                        bf.add(new BioFormatsMifSource(f));
                        updateProgress(0.05 * (fi + 1) / files.size(), 1);
                    }
                    logMem(log, "after opening all Bio-Formats readers");
                    if (isCancelled()) return null;

                    for (int i = 0; i < bf.size(); i++) {
                        channelNamesPerSource.add(bf.get(i).getChannelNames());
                        labels.add(stem(files.get(i).getName()));
                    }

                    RegistrationOrchestrator.Config cfg = new RegistrationOrchestrator.Config();
                    cfg.dapiNameMatch = dapiName;
                    cfg.stage1TargetLongPx = stage1Long;
                    cfg.stage2TargetLongPx = stage2Long;
                    cfg.stage1Params.nFeatures(nFeatures);
                    cfg.stage2Params.nFeatures(nFeatures);
                    cfg.enableStage3 = enableStage3;
                    cfg.stage3.numWindows = stage3NumWindows;
                    cfg.stage3.windowSizePx = stage3WindowSize;

                    int nPairs = bf.size() - 1;
                    for (int i = 1; i < bf.size(); i++) {
                        if (isCancelled()) return null;
                        updateMessage(String.format("Registering pair %d/%d (Stage 1+2%s)…",
                                i, nPairs, enableStage3 ? "+3" : ""));
                        double pairStartProgress = 0.05 + 0.55 * (i - 1) / nPairs;
                        double pairEndProgress = 0.05 + 0.55 * i / nPairs;
                        updateProgress(pairStartProgress, 1);

                        appendLog(log, String.format("  Registering %s -> %s",
                                files.get(i).getName(), files.get(0).getName()));
                        RegistrationOrchestrator.Result r = RegistrationOrchestrator.run(
                                bf.get(0), bf.get(i), cfg, msg -> appendLog(log, msg));
                        appendLog(log, String.format("    stage 2 inliers %d/%d, reproj median %.2fpx @L2",
                                r.stages.stage2.nInliers, r.stages.stage2.nMatchesPostPrefilter,
                                r.stages.stage2.medianReprojErrPx));
                        if (r.stage3 != null) {
                            appendLog(log, String.format("    stage 3 inliers %d/%d, reproj median %.2fpx @full-res",
                                    r.stage3.finalInliers, r.stage3.totalPointPairs,
                                    r.stage3.reprojMedianPx));
                        }
                        AffineTransform aff = new AffineTransform(
                                r.matrixFullRes[0][0], r.matrixFullRes[1][0],
                                r.matrixFullRes[0][1], r.matrixFullRes[1][1],
                                r.matrixFullRes[0][2], r.matrixFullRes[1][2]);
                        movingAffines.add(aff);

                        // Per-pair diagnostics: matrix files + QC PNGs alongside the OME-TIFF.
                        // Don't fail the whole task if diagnostics write fails — just warn.
                        try {
                            writePairDiagnostics(outPath, files.get(0), files.get(i),
                                    i, nPairs, r, bf.get(0), bf.get(i), log);
                        } catch (Throwable t) {
                            appendLog(log, "  warning: failed to write diagnostics: " + t.getMessage());
                            logger.warn("Failed to write pair {} diagnostics", i, t);
                        }

                        logMem(log, "after registering pair " + i);
                        updateProgress(pairEndProgress, 1);
                    }
                } finally {
                    for (MifImageSource s : bf) {
                        try { s.close(); } catch (Exception ignored) {}
                    }
                    bf.clear();
                }
                // Hint to the GC that we just released a lot of native resources
                System.gc();
                logMem(log, "after closing Bio-Formats readers (Phase A done)");
                if (isCancelled()) return null;

                // -------- Phase B: merge + write --------
                // The PYVIPS backend bypasses QuPath ImageServers entirely —
                // libvips reads source qptiffs natively. Branch out early.
                OmeTiffMergeWriter.WriterBackend effectiveBackend = backend;
                if (effectiveBackend == OmeTiffMergeWriter.WriterBackend.PYVIPS) {
                    String pyvipsVer = PyVipsWriter.detectPythonPyVips();
                    if (pyvipsVer == null) {
                        appendLog(log, "  pyvips not found (need Python 3 with `pip install pyvips`); "
                                + "falling back to libvips backend.");
                        effectiveBackend = OmeTiffMergeWriter.WriterBackend.LIBVIPS;
                    } else {
                        appendLog(log, "  pyvips " + pyvipsVer + " detected — running native streaming merge");
                    }
                }
                if (effectiveBackend == OmeTiffMergeWriter.WriterBackend.PYVIPS) {
                    updateProgress(0.60, 1);
                    updateMessage("Writing OME-TIFF via pyvips…");
                    appendLog(log, "Phase B: pyvips merge + write (no QuPath ImageServers, no intermediate file)");
                    appendLog(log, String.format(
                            "  Backend=PYVIPS, Compression=%s, tileSize=%d, pyramid=%s",
                            compression, tileSize, pyramidMode));

                    List<MergedChannelLayout.ChannelEntry> layout =
                            MergedChannelLayout.build(channelNamesPerSource, labels, dapiName, keepMovingDapi);
                    appendLog(log, "  Merged layout has " + layout.size() + " channels"
                            + (keepMovingDapi ? " (keeping all DAPI channels)" : " (moving DAPI dropped)"));

                    PyVipsWriter.Recipe recipe = buildPyVipsRecipe(
                            files, channelNamesPerSource, labels, dapiName, keepMovingDapi,
                            movingAffines, outPath, compression, tileSize, pyramidMode);
                    try {
                        PyVipsWriter.run(recipe,
                                msg -> appendLog(log, msg),
                                proc -> processSink.set(proc));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while running pyvips", ie);
                    }
                    appendLog(log, "Done.");
                    updateProgress(1.0, 1);
                    updateMessage("Done.");
                    return null;
                }
                // From here on, effectiveBackend is LIBVIPS or BIO_FORMATS — re-use the
                // existing QuPath ImageServer route. (We can't reassign the
                // 'backend' parameter from inside this lambda.)
                final OmeTiffMergeWriter.WriterBackend backendForWrite = effectiveBackend;

                updateProgress(0.60, 1);
                updateMessage("Opening QuPath ImageServers…");
                appendLog(log, "Phase B: merge + write OME-TIFF");
                List<ImageServer<BufferedImage>> qp = new ArrayList<>();
                try {
                    for (int fi = 0; fi < files.size(); fi++) {
                        File f = files.get(fi);
                        appendLog(log, "  Opening QuPath ImageServer for " + f.getName());
                        qp.add(ImageServerProvider.buildServer(f.getAbsolutePath(),
                                BufferedImage.class));
                        updateProgress(0.60 + 0.05 * (fi + 1) / files.size(), 1);
                    }
                    logMem(log, "after opening QuPath ImageServers");

                    List<MergedServerFactory.MovingEntry> moving = new ArrayList<>();
                    for (int i = 0; i < movingAffines.size(); i++) {
                        moving.add(new MergedServerFactory.MovingEntry(qp.get(i + 1),
                                movingAffines.get(i)));
                    }

                    updateProgress(0.65, 1);
                    updateMessage("Building merged virtual server…");
                    List<MergedChannelLayout.ChannelEntry> layout =
                            MergedChannelLayout.build(channelNamesPerSource, labels, dapiName, keepMovingDapi);
                    appendLog(log, "  Merged layout has " + layout.size() + " channels"
                            + (keepMovingDapi ? " (keeping all DAPI channels)" : " (moving DAPI dropped)"));

                    ImageServer<BufferedImage> merged = MergedServerFactory.build(
                            qp.get(0), moving, layout);

                    updateProgress(0.70, 1);
                    updateMessage("Writing OME-TIFF (this is usually the slowest step)…");
                    appendLog(log, "  Writing OME-TIFF: " + outPath);
                    appendLog(log, String.format(
                            "  Backend=%s, Compression=%s, tileSize=%d, writeThreads=%d, pyramid=%s",
                            backendForWrite, compression, tileSize, nWriteThreads, pyramidMode));
                    OmeTiffMergeWriter.Options writeOpts = new OmeTiffMergeWriter.Options();
                    writeOpts.compression = compression;
                    writeOpts.tileSize = tileSize;
                    writeOpts.nWriteThreads = nWriteThreads;
                    writeOpts.pyramidMode = pyramidMode;
                    writeOpts.backend = backendForWrite;
                    // Compute the intermediate path now (same logic libvips backend uses)
                    // so the cancel handler knows what file to delete.
                    if (backendForWrite == OmeTiffMergeWriter.WriterBackend.LIBVIPS) {
                        Path interm = computeIntermediatePath(outPath);
                        intermediatePathSink.set(interm);
                    }
                    OmeTiffMergeWriter.write(merged, outPath, writeOpts,
                            msg -> appendLog(log, msg),
                            proc -> processSink.set(proc));
                    appendLog(log, "Done.");

                    // Hint the JVM to release tile cache + Bio-Formats buffers now
                    // that we're done with them. Without this, the memory bump from
                    // the write phase can stay parked until the next major GC.
                    merged = null;
                    System.gc();
                    logMem(log, "after write + GC");

                    updateProgress(1.0, 1);
                    updateMessage("Done.");
                    return null;
                } finally {
                    for (ImageServer<BufferedImage> s : qp) {
                        try { s.close(); } catch (Exception ignored) {}
                    }
                }
            }
        };
    }

    /** Run cleanup on a daemon thread so the FX thread isn't blocked by deletion retries. */
    private static void runCleanupAsync(String outputPath, Path intermediate, TextArea log,
                                        Runnable onDone) {
        Thread t = new Thread(() -> {
            cleanupCancelledArtifacts(outputPath, intermediate, log);
            if (onDone != null) Platform.runLater(onDone);
        }, "mif-merge-cleanup");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Best-effort delete of files we know we were writing when a cancel /
     * failure hit. On Windows a file may still be locked by a not-yet-terminated
     * writer thread; we retry a few times with backoff before giving up.
     */
    private static void cleanupCancelledArtifacts(String outputPath, Path intermediate, TextArea log) {
        if (outputPath != null) {
            tryDelete(Path.of(outputPath), log);
        }
        if (intermediate != null) {
            tryDelete(intermediate, log);
        }
    }

    /**
     * Mirror the logic in {@link OmeTiffMergeWriter#writeWithLibVips} for naming
     * the intermediate. Has to stay in sync with that method.
     */
    private static Path computeIntermediatePath(String outputPath) {
        Path output = Path.of(outputPath).toAbsolutePath();
        Path parent = output.getParent();
        if (parent == null) parent = Path.of(".");
        String stem = output.getFileName().toString();
        String lower = stem.toLowerCase();
        if (lower.endsWith(".ome.tif")) stem = stem.substring(0, stem.length() - 8);
        else if (lower.endsWith(".ome.tiff")) stem = stem.substring(0, stem.length() - 9);
        else if (lower.endsWith(".tif")) stem = stem.substring(0, stem.length() - 4);
        else if (lower.endsWith(".tiff")) stem = stem.substring(0, stem.length() - 5);
        return parent.resolve(stem + ".intermediate.tif");
    }

    private static void tryDelete(Path path, TextArea log) {
        if (path == null || !path.toFile().exists()) return;
        long sizeMb = path.toFile().length() / 1024 / 1024;
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                Files.deleteIfExists(path);
                appendLog(log, String.format("  deleted %s (%d MB)", path.getFileName(), sizeMb));
                return;
            } catch (IOException e) {
                if (attempt == 5) {
                    appendLog(log, String.format(
                            "  warning: could not delete %s after %d attempts (%s). "
                                    + "You may need to delete it manually.",
                            path, attempt, e.getMessage()));
                } else {
                    try { Thread.sleep(500L * attempt); } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    /** Map the GUI dropdown label to an OmeTiffMergeWriter.WriterBackend. */
    private static OmeTiffMergeWriter.WriterBackend backendFromChoice(String s) {
        if (s == null) return OmeTiffMergeWriter.WriterBackend.PYVIPS;
        if (s.startsWith("Bio-Formats")) return OmeTiffMergeWriter.WriterBackend.BIO_FORMATS;
        if (s.startsWith("libvips")) return OmeTiffMergeWriter.WriterBackend.LIBVIPS;
        return OmeTiffMergeWriter.WriterBackend.PYVIPS;
    }

    /**
     * Convert the per-source channel name lists + affines into a
     * {@link PyVipsWriter.Recipe} the Python script can read directly.
     *
     * <p>Channel page index assumption: for Vectra Polaris qptiff full-resolution
     * channels live in TIFF pages 0..nChannels-1 (one channel per page,
     * channels-first then pyramid). This holds for every Vectra qptiff we've
     * tested. If a non-Vectra qptiff comes in with a different layout this
     * needs revisiting.
     */
    private static PyVipsWriter.Recipe buildPyVipsRecipe(
            List<File> files,
            List<List<String>> channelNamesPerSource,
            List<String> labels,
            String dapiNameMatch,
            boolean keepMovingDapi,
            List<AffineTransform> movingAffines,
            String outputPath,
            OMEPyramidWriter.CompressionType compression,
            int tileSize,
            OmeTiffMergeWriter.PyramidMode pyramidMode) {

        String needle = dapiNameMatch == null ? "dapi" : dapiNameMatch.toLowerCase();

        // Build fixed source spec
        List<String> fixedChans = channelNamesPerSource.get(0);
        String fixedLabel = labels.get(0);
        List<PyVipsWriter.ChannelSpec> fixedChannelSpecs = new ArrayList<>();
        for (int c = 0; c < fixedChans.size(); c++) {
            String name = fixedChans.get(c);
            boolean isDapi = name != null && name.toLowerCase().contains(needle);
            // Fixed always keeps everything
            String outName = (fixedLabel != null && !fixedLabel.isEmpty())
                    ? fixedLabel + " | " + (name != null ? name : "ch" + c)
                    : (name != null ? name : "ch" + c);
            fixedChannelSpecs.add(new PyVipsWriter.ChannelSpec(
                    c, name != null ? name : "ch" + c, isDapi, true, outName));
        }
        PyVipsWriter.SourceSpec fixedSpec = new PyVipsWriter.SourceSpec(
                files.get(0).getAbsolutePath(), fixedChannelSpecs, null);

        // Build moving source specs
        List<PyVipsWriter.SourceSpec> movingSpecs = new ArrayList<>();
        for (int srcIdx = 1; srcIdx < channelNamesPerSource.size(); srcIdx++) {
            List<String> chans = channelNamesPerSource.get(srcIdx);
            String label = labels.get(srcIdx);
            AffineTransform aff = movingAffines.get(srcIdx - 1);
            double[] flat = new double[6];
            aff.getMatrix(flat);   // [m00, m10, m01, m11, m02, m12]
            double[][] matrix = new double[][] {
                    { flat[0], flat[2], flat[4] },
                    { flat[1], flat[3], flat[5] },
                    { 0.0,      0.0,     1.0   }
            };
            List<PyVipsWriter.ChannelSpec> chanSpecs = new ArrayList<>();
            for (int c = 0; c < chans.size(); c++) {
                String name = chans.get(c);
                boolean isDapi = name != null && name.toLowerCase().contains(needle);
                boolean include = !isDapi || keepMovingDapi;
                String outName = include
                        ? (label != null && !label.isEmpty()
                            ? label + " | " + (name != null ? name : "ch" + c)
                            : (name != null ? name : "ch" + c))
                        : null;
                chanSpecs.add(new PyVipsWriter.ChannelSpec(
                        c, name != null ? name : "ch" + c, isDapi, include, outName));
            }
            movingSpecs.add(new PyVipsWriter.SourceSpec(
                    files.get(srcIdx).getAbsolutePath(), chanSpecs, matrix));
        }

        return new PyVipsWriter.Recipe(
                fixedSpec, movingSpecs, outputPath,
                compression, tileSize, pyramidMode);
    }

    /** Map the GUI dropdown label to an OmeTiffMergeWriter.PyramidMode. */
    private static OmeTiffMergeWriter.PyramidMode pyramidModeFromChoice(String s) {
        if (s == null) return OmeTiffMergeWriter.PyramidMode.DYADIC;
        if (s.startsWith("Sparse")) return OmeTiffMergeWriter.PyramidMode.SPARSE;
        if (s.startsWith("Single")) return OmeTiffMergeWriter.PyramidMode.SINGLE;
        return OmeTiffMergeWriter.PyramidMode.DYADIC;
    }

    /** Map the GUI dropdown label to an OMEPyramidWriter.CompressionType. */
    private static OMEPyramidWriter.CompressionType compressionFromChoice(String s) {
        if (s == null) return OMEPyramidWriter.CompressionType.LZW;
        if (s.startsWith("Uncompressed")) return OMEPyramidWriter.CompressionType.UNCOMPRESSED;
        if (s.startsWith("LZW")) return OMEPyramidWriter.CompressionType.LZW;
        if (s.startsWith("ZLIB")) return OMEPyramidWriter.CompressionType.ZLIB;
        if (s.startsWith("J2K_LOSSY") || s.startsWith("J2K")) return OMEPyramidWriter.CompressionType.J2K_LOSSY;
        return OMEPyramidWriter.CompressionType.LZW;
    }

    /** Log current heap usage to the task log so the user can spot allocation spikes. */
    private static void logMem(TextArea log, String label) {
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);
        long heapMax = rt.maxMemory() / (1024L * 1024L);
        appendLog(log, String.format("  [mem] %s: heap used %d MB / max %d MB", label, used, heapMax));
    }

    /**
     * Write per-pair diagnostic files next to the output OME-TIFF:
     *   <stem>[-pairN-]matrix_full_res.json
     *   <stem>[-pairN-]matrices.txt
     *   <stem>[-pairN-]qc_{checkerboard,abs_diff,overlay}.png
     */
    private static void writePairDiagnostics(String outOmeTiffPath,
                                             File fixedFile, File movingFile,
                                             int pairIndex, int totalPairs,
                                             RegistrationOrchestrator.Result r,
                                             MifImageSource bfFixed, MifImageSource bfMoving,
                                             TextArea log) throws IOException {
        Path omePath = Path.of(outOmeTiffPath).toAbsolutePath();
        Path outDir = omePath.getParent();
        if (outDir == null) outDir = Path.of(".");
        // Strip .ome.tif / .ome.tiff / .tif / .tiff
        String stem = omePath.getFileName().toString();
        for (String suf : new String[] {".ome.tiff", ".ome.tif", ".tiff", ".tif"}) {
            if (stem.toLowerCase().endsWith(suf)) {
                stem = stem.substring(0, stem.length() - suf.length());
                break;
            }
        }
        String prefix = totalPairs > 1
                ? stem + "-pair" + pairIndex + "-"
                : stem + "-";

        Path matJson = outDir.resolve(prefix + "matrix_full_res.json");
        Path matTxt  = outDir.resolve(prefix + "matrices.txt");
        Files.writeString(matJson, matrixJson(r));
        Files.writeString(matTxt, matrixTxt(fixedFile, movingFile, r));
        appendLog(log, "    wrote " + matJson.getFileName() + " + " + matTxt.getFileName());

        // QC PNGs: re-read stage 1 DAPI (cheap, ~16 MB each) and warp moving via stage 1 matrix.
        BufferedImage s1Fixed = bfFixed.readChannelAtLevel(r.fixedDapiChannel, r.stage1Levels.levelFixed);
        BufferedImage s1Moving = bfMoving.readChannelAtLevel(r.movingDapiChannel, r.stage1Levels.levelMoving);
        AffineTransform affS1 = MatrixRescaler.toAffineTransform(r.stages.stage1.matrix);
        QcVisualizer.write(s1Fixed, s1Moving, affS1, outDir, prefix);
        appendLog(log, "    wrote " + prefix + "qc_{checkerboard,abs_diff,overlay}.png");
    }

    private static String matrixJson(RegistrationOrchestrator.Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"matrix_full_res\": ").append(matrixArrJson(r.matrixFullRes)).append(",\n");
        sb.append("  \"matrix_stage2\": ").append(matrixArrJson(r.stages.matrixAtStage2Resolution)).append(",\n");
        sb.append("  \"matrix_stage1\": ").append(matrixArrJson(r.stages.stage1.matrix)).append(",\n");
        sb.append("  \"stage1_inliers\": ").append(r.stages.stage1.nInliers).append(",\n");
        sb.append("  \"stage1_reproj_median_px\": ").append(String.format(Locale.ROOT, "%.4f", r.stages.stage1.medianReprojErrPx)).append(",\n");
        sb.append("  \"stage2_inliers\": ").append(r.stages.stage2.nInliers).append(",\n");
        sb.append("  \"stage2_reproj_median_px\": ").append(String.format(Locale.ROOT, "%.4f", r.stages.stage2.medianReprojErrPx)).append(",\n");
        if (r.stage3 != null) {
            sb.append("  \"stage3_enabled\": true,\n");
            sb.append("  \"stage3_inliers\": ").append(r.stage3.finalInliers).append(",\n");
            sb.append("  \"stage3_total_point_pairs\": ").append(r.stage3.totalPointPairs).append(",\n");
            sb.append("  \"stage3_reproj_median_full_res_px\": ").append(String.format(Locale.ROOT, "%.4f", r.stage3.reprojMedianPx)).append(",\n");
            sb.append("  \"stage3_windows_succeeded\": ").append(r.stage3.windowsSucceeded).append(",\n");
        } else {
            sb.append("  \"stage3_enabled\": false,\n");
        }
        sb.append("  \"fixed_dapi_channel\": ").append(r.fixedDapiChannel).append(",\n");
        sb.append("  \"moving_dapi_channel\": ").append(r.movingDapiChannel).append("\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String matrixArrJson(double[][] m) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 3; i++) {
            sb.append("[");
            for (int j = 0; j < 3; j++) {
                sb.append(String.format(Locale.ROOT, "%.10e", m[i][j]));
                if (j < 2) sb.append(", ");
            }
            sb.append(i < 2 ? "], " : "]");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String matrixTxt(File fixedFile, File movingFile, RegistrationOrchestrator.Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append("Fixed : ").append(fixedFile.getName()).append("\n");
        sb.append("Moving: ").append(movingFile.getName()).append("\n\n");

        sb.append("Stage 1 (coarse): ").append(r.stage1Levels).append("\n");
        sb.append(String.format(Locale.ROOT, "  inliers=%d/%d (%.1f%%), reproj median=%.2fpx p95=%.2fpx%n",
                r.stages.stage1.nInliers, r.stages.stage1.nMatchesPostPrefilter,
                100.0 * r.stages.stage1.inlierRatio,
                r.stages.stage1.medianReprojErrPx, r.stages.stage1.p95ReprojErrPx));

        sb.append("Stage 2 (refine): ").append(r.stage2Levels).append("\n");
        sb.append(String.format(Locale.ROOT, "  inliers=%d/%d (%.1f%%), reproj median=%.2fpx p95=%.2fpx%n",
                r.stages.stage2.nInliers, r.stages.stage2.nMatchesPostPrefilter,
                100.0 * r.stages.stage2.inlierRatio,
                r.stages.stage2.medianReprojErrPx, r.stages.stage2.p95ReprojErrPx));

        if (r.stage3 != null) {
            sb.append("Stage 3 (windowed full-res):\n");
            sb.append(String.format(Locale.ROOT,
                    "  inliers=%d/%d (%.1f%%), reproj median=%.2fpx p95=%.2fpx @full-res, %d/%d windows succeeded%n",
                    r.stage3.finalInliers, r.stage3.totalPointPairs,
                    100.0 * r.stage3.inlierRatio,
                    r.stage3.reprojMedianPx, r.stage3.reprojP95Px,
                    r.stage3.windowsSucceeded, r.stage3.windowsAttempted));
        }

        sb.append("\nFULL-RESOLUTION matrix (moving_full -> fixed_full):\n");
        for (int i = 0; i < 3; i++) {
            sb.append(String.format(Locale.ROOT, "  [%+.6e %+.6e %+.6e]%n",
                    r.matrixFullRes[i][0], r.matrixFullRes[i][1], r.matrixFullRes[i][2]));
        }
        double a = r.matrixFullRes[0][0], b = r.matrixFullRes[0][1];
        double c = r.matrixFullRes[1][0], d = r.matrixFullRes[1][1];
        sb.append("\nAffine decomposition:\n");
        sb.append(String.format(Locale.ROOT, "  rotation   ~ %+.4f deg%n",
                Math.toDegrees(Math.atan2(c, a))));
        sb.append(String.format(Locale.ROOT, "  scale x/y  ~ %.5f / %.5f%n",
                Math.hypot(a, c), Math.hypot(b, d)));
        sb.append(String.format(Locale.ROOT, "  translation~ (%+.1f, %+.1f) px%n",
                r.matrixFullRes[0][2], r.matrixFullRes[1][2]));
        return sb.toString();
    }

    private static void appendLog(TextArea log, String line) {
        logger.info("{}", line);
        Platform.runLater(() -> log.appendText(line + "\n"));
    }

    private static void swap(ObservableList<File> list, int idx, int delta) {
        int j = idx + delta;
        if (idx < 0 || j < 0 || j >= list.size()) return;
        File a = list.get(idx);
        list.set(idx, list.get(j));
        list.set(j, a);
    }

    private static String stem(String filename) {
        int dot = filename.indexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private static void showError(Window owner, String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
        if (owner != null) a.initOwner(owner);
        a.showAndWait();
    }

    private static void showInfo(Window owner, String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK);
        if (owner != null) a.initOwner(owner);
        a.showAndWait();
    }

    /**
     * A more prominent success alert: shows the output path, elapsed time, and a
     * brief hint to open the file in QuPath. Made bold/colored so the user
     * notices it immediately, with an audible beep that precedes it.
     */
    private static void showSuccessAlert(Window owner, String outPath, long elapsedSec) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setTitle("MIF Merge — Done");
        a.setHeaderText(String.format("Merge complete in %d seconds.", elapsedSec));
        a.setContentText("Output written to:\n  " + outPath
                + "\n\nOpen it via File > Open in QuPath to view the merged channels.");
        if (owner != null) a.initOwner(owner);
        a.getDialogPane().setMinWidth(500);
        a.showAndWait();
    }
}
