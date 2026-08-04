package io.github.guillermodubon.musicplayer.controllers.ui.components.downloadComponents;

import javafx.stage.DirectoryChooser;
import javafx.stage.Window;
import io.github.guillermodubon.musicplayer.services.downloads.preferences.DownloadPreferences;

import java.io.File;

public final class DownloadDirectoryChooser {

    private DownloadDirectoryChooser() {}

    public static File chooseDirectory(Window parentWindow, String title, File defaultDirectory) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(title);

        File initial = defaultDirectory;
        if (initial == null || !initial.exists() || !initial.isDirectory()) {
            initial = DownloadPreferences.getDefaultDownloadsDirectory();
        }

        chooser.setInitialDirectory(initial);
        return chooser.showDialog(parentWindow);
    }
}