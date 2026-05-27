package qupath.ext.mifmerge;

import org.controlsfx.control.action.Action;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.mifmerge.gui.MifMergeCommand;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.tools.MenuTools;

/**
 * ServiceLoader entry point. Registers a {@code Extensions > MIF Merge > Run merge…}
 * menu item that opens {@link MifMergeCommand}.
 */
public class MifMergeExtension implements QuPathExtension, GitHubProject {

    private static final Logger logger = LoggerFactory.getLogger(MifMergeExtension.class);

    private boolean isInstalled = false;

    @Override
    public void installExtension(QuPathGUI qupath) {
        if (isInstalled) {
            return;
        }
        try {
            MifMergeCommand cmd = new MifMergeCommand(qupath);
            MenuTools.addMenuItems(
                    qupath.getMenu("Extensions>MIF Merge", true),
                    new Action("Run merge…", e -> cmd.run()));
            isInstalled = true;
            logger.info("MIF Merge extension installed (menu Extensions > MIF Merge).");
        } catch (Exception e) {
            logger.error("Failed to install MIF Merge menu items", e);
        }
    }

    @Override
    public String getName() {
        return "MIF Merge extension";
    }

    @Override
    public String getDescription() {
        return "Register multiple qptiff WSIs on DAPI (two-stage SIFT) and export a merged pyramidal OME-TIFF.";
    }

    @Override
    public Version getQuPathVersion() {
        return Version.parse("0.8.0-SNAPSHOT");
    }

    @Override
    public GitHubRepo getRepository() {
        return GitHubRepo.create(getName(), "local", "qupath-extension-mif-merge");
    }
}
