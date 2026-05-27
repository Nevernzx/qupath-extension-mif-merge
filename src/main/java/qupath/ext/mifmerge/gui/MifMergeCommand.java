package qupath.ext.mifmerge.gui;

import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
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

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        grid.setPadding(new Insets(8, 0, 8, 0));
        grid.addRow(0, new Label("Output:"), outRow);
        grid.addRow(1, new Label("DAPI channel match:"), dapiField);
        grid.addRow(2, new Label("Stage 1 long side (px):"), stage1Long);
        grid.addRow(3, new Label("Stage 2 long side (px):"), stage2Long);
        GridPane.setHgrow(outRow, Priority.ALWAYS);

        // --- Progress + log ---
        TextArea log = new TextArea();
        log.setEditable(false);
        log.setPrefRowCount(10);
        ProgressBar progress = new ProgressBar(0);
        progress.setPrefWidth(Double.MAX_VALUE);

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
            progress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            log.clear();

            Task<Void> task = makeTask(
                    new ArrayList<>(files), outPath,
                    dapiField.getText(),
                    stage1Long.getValue(), stage2Long.getValue(),
                    log);
            task.setOnSucceeded(e -> Platform.runLater(() -> {
                progress.setProgress(1.0);
                runBtn.setDisable(false);
                showInfo(stage, "Merge complete:\n" + outPath);
            }));
            task.setOnFailed(e -> Platform.runLater(() -> {
                progress.setProgress(0);
                runBtn.setDisable(false);
                Throwable t = task.getException();
                logger.error("MIF Merge failed", t);
                showError(stage, "Failed: " + (t == null ? "(no exception)" : t.getMessage()));
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
                                TextArea log) {
        return new Task<>() {
            @Override
            protected Void call() throws Exception {
                List<MifImageSource> bf = new ArrayList<>();
                List<ImageServer<BufferedImage>> qp = new ArrayList<>();
                try {
                    for (File f : files) {
                        appendLog(log, "Opening " + f.getName());
                        bf.add(new BioFormatsMifSource(f));
                        qp.add(ImageServerProvider.buildServer(f.getAbsolutePath(),
                                BufferedImage.class));
                    }
                    if (isCancelled()) return null;

                    List<MergedServerFactory.MovingEntry> moving = new ArrayList<>();
                    RegistrationOrchestrator.Config cfg = new RegistrationOrchestrator.Config();
                    cfg.dapiNameMatch = dapiName;
                    cfg.stage1TargetLongPx = stage1Long;
                    cfg.stage2TargetLongPx = stage2Long;

                    for (int i = 1; i < bf.size(); i++) {
                        if (isCancelled()) return null;
                        appendLog(log, String.format("Registering %s -> %s",
                                files.get(i).getName(), files.get(0).getName()));
                        RegistrationOrchestrator.Result r = RegistrationOrchestrator.run(
                                bf.get(0), bf.get(i), cfg);
                        appendLog(log, String.format("  stage 2 inliers %d/%d, reproj median %.2fpx @L2",
                                r.stages.stage2.nInliers, r.stages.stage2.nMatchesPostPrefilter,
                                r.stages.stage2.medianReprojErrPx));
                        AffineTransform aff = new AffineTransform(
                                r.matrixFullRes[0][0], r.matrixFullRes[1][0],
                                r.matrixFullRes[0][1], r.matrixFullRes[1][1],
                                r.matrixFullRes[0][2], r.matrixFullRes[1][2]);
                        moving.add(new MergedServerFactory.MovingEntry(qp.get(i), aff));
                    }

                    List<List<String>> chans = new ArrayList<>();
                    List<String> labels = new ArrayList<>();
                    for (int i = 0; i < bf.size(); i++) {
                        chans.add(bf.get(i).getChannelNames());
                        labels.add(stem(files.get(i).getName()));
                    }
                    List<MergedChannelLayout.ChannelEntry> layout =
                            MergedChannelLayout.build(chans, labels, dapiName);
                    appendLog(log, "Merged layout has " + layout.size() + " channels");

                    ImageServer<BufferedImage> merged = MergedServerFactory.build(
                            qp.get(0), moving, layout);

                    appendLog(log, "Writing OME-TIFF: " + outPath);
                    OmeTiffMergeWriter.write(merged, outPath, new OmeTiffMergeWriter.Options());
                    appendLog(log, "Done.");
                    return null;
                } finally {
                    for (MifImageSource s : bf) s.close();
                    for (ImageServer<BufferedImage> s : qp) try { s.close(); } catch (Exception ignored) {}
                }
            }
        };
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
}
