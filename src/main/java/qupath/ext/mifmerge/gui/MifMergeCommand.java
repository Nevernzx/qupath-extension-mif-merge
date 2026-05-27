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

import java.awt.Toolkit;
import qupath.ext.mifmerge.core.MergedChannelLayout;
import qupath.ext.mifmerge.core.MifImageSource;
import qupath.ext.mifmerge.core.RegistrationOrchestrator;
import qupath.ext.mifmerge.io.BioFormatsMifSource;
import qupath.ext.mifmerge.merge.MergedServerFactory;
import qupath.ext.mifmerge.merge.OmeTiffMergeWriter;
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

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        grid.setPadding(new Insets(8, 0, 8, 0));
        grid.addRow(0, new Label("Output:"), outRow);
        grid.addRow(1, new Label("DAPI channel match:"), dapiField);
        grid.addRow(2, new Label("Stage 1 long side (px):"), stage1Long);
        grid.addRow(3, new Label("Stage 2 long side (px):"), stage2Long);
        grid.addRow(4, new Label("SIFT keypoints per image:"), nFeaturesSpinner);
        grid.add(stage3Enable, 0, 5, 2, 1);
        grid.addRow(6, new Label("  Stage 3 windows:"), stage3NumWindows);
        grid.addRow(7, new Label("  Stage 3 window size (px):"), stage3WindowSize);
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

        // --- Bottom row: Run + Close ---
        Button runBtn = new Button("Run merge");
        Button closeBtn = new Button("Close");
        HBox bottomRow = new HBox(8, runBtn, closeBtn);

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

        Scene scene = new Scene(content, 640, 600);
        stage.setScene(scene);

        SimpleObjectProperty<Task<Void>> currentTask = new SimpleObjectProperty<>();

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
            log.clear();
            long taskStart = System.currentTimeMillis();

            Task<Void> task = makeTask(
                    new ArrayList<>(files), outPath,
                    dapiField.getText(),
                    stage1Long.getValue(), stage2Long.getValue(),
                    nFeaturesSpinner.getValue(),
                    stage3Enable.isSelected(),
                    stage3NumWindows.getValue(),
                    stage3WindowSize.getValue(),
                    log);
            // Bind the progress bar + status label to the Task's progress/message.
            // Task#updateProgress and #updateMessage (called from the worker thread)
            // are thread-safe and update these properties on the FX thread.
            progress.progressProperty().bind(task.progressProperty());
            statusLabel.textProperty().bind(task.messageProperty());

            task.setOnSucceeded(e -> Platform.runLater(() -> {
                // Unbind so we can override
                progress.progressProperty().unbind();
                statusLabel.textProperty().unbind();
                progress.setProgress(1.0);
                long elapsed = (System.currentTimeMillis() - taskStart) / 1000;
                statusLabel.setText(String.format(" ✓ Merge complete in %ds", elapsed));
                statusLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #2a8000;");
                runBtn.setDisable(false);
                // Audible cue
                try { Toolkit.getDefaultToolkit().beep(); } catch (Throwable ignored) {}
                showSuccessAlert(stage, outPath, elapsed);
            }));
            task.setOnFailed(e -> Platform.runLater(() -> {
                progress.progressProperty().unbind();
                statusLabel.textProperty().unbind();
                progress.setProgress(0);
                statusLabel.setText(" ✗ Failed");
                statusLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #c00000;");
                runBtn.setDisable(false);
                Throwable t = task.getException();
                logger.error("MIF Merge failed", t);
                try { Toolkit.getDefaultToolkit().beep(); } catch (Throwable ignored) {}
                showError(stage, "Failed: " + (t == null ? "(no exception)" : t.getMessage()));
            }));
            task.setOnCancelled(e -> Platform.runLater(() -> {
                progress.progressProperty().unbind();
                statusLabel.textProperty().unbind();
                progress.setProgress(0);
                statusLabel.setText(" — Cancelled");
                statusLabel.setStyle("-fx-font-weight: bold;");
                runBtn.setDisable(false);
            }));
            currentTask.set(task);
            Thread thr = new Thread(task, "mif-merge-task");
            thr.setDaemon(true);
            thr.start();
        });

        closeBtn.setOnAction(evt -> stage.close());

        stage.setOnCloseRequest(evt -> {
            Task<Void> t = currentTask.get();
            if (t != null && t.isRunning()) {
                t.cancel(true);
            }
        });

        stage.show();   // non-blocking
    }

    private Task<Void> makeTask(List<File> files, String outPath,
                                String dapiName, int stage1Long, int stage2Long,
                                int nFeatures,
                                boolean enableStage3, int stage3NumWindows, int stage3WindowSize,
                                TextArea log) {
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
                            MergedChannelLayout.build(channelNamesPerSource, labels, dapiName);
                    appendLog(log, "  Merged layout has " + layout.size() + " channels");

                    ImageServer<BufferedImage> merged = MergedServerFactory.build(
                            qp.get(0), moving, layout);

                    updateProgress(0.70, 1);
                    updateMessage("Writing OME-TIFF (this is usually the slowest step)…");
                    appendLog(log, "  Writing OME-TIFF: " + outPath);
                    OmeTiffMergeWriter.write(merged, outPath, new OmeTiffMergeWriter.Options());
                    appendLog(log, "Done.");
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

    /** Log current heap usage to the task log so the user can spot allocation spikes. */
    private static void logMem(TextArea log, String label) {
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);
        long heapMax = rt.maxMemory() / (1024L * 1024L);
        appendLog(log, String.format("  [mem] %s: heap used %d MB / max %d MB", label, used, heapMax));
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
