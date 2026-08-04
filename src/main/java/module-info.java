module io.github.guillermodubon.musicplayer {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.web;
    requires javafx.media;
    requires org.controlsfx.controls;
    requires org.kordamp.ikonli.javafx;
    requires com.almasb.fxgl.all;
    requires com.fasterxml.jackson.core;
    requires annotations;
    requires jdk.compiler;
    requires okhttp3;
    requires com.google.gson;
    requires javafx.swing;
    requires org.xerial.sqlitejdbc;
    requires java.prefs;
    requires jdk.jfr;

    exports io.github.guillermodubon.musicplayer.repository;
    opens io.github.guillermodubon.musicplayer.repository to javafx.fxml;
    exports io.github.guillermodubon.musicplayer;
    opens io.github.guillermodubon.musicplayer to javafx.fxml;
    exports io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistManagementDialogs;
    opens io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistManagementDialogs to javafx.fxml;
    exports io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistContentManagementDialogs;
    opens io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistContentManagementDialogs to javafx.fxml;
    exports io.github.guillermodubon.musicplayer.controllers.ui.components.cards;
    opens io.github.guillermodubon.musicplayer.controllers.ui.components.cards to javafx.fxml;
    exports io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.base;
    opens io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.base to javafx.fxml;
    exports io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistManagementDialogs.common;
    opens io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistManagementDialogs.common to javafx.fxml;
    exports io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistContentManagementDialogs.common;
    opens io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistContentManagementDialogs.common to javafx.fxml;
    exports io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data;
    opens io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data to javafx.fxml;
    exports io.github.guillermodubon.musicplayer.controllers.ui.components.cards.factory;
    opens io.github.guillermodubon.musicplayer.controllers.ui.components.cards.factory to javafx.fxml;
    exports io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers;
    opens io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers to javafx.fxml;
    exports io.github.guillermodubon.musicplayer.services.navigation;
    opens io.github.guillermodubon.musicplayer.services.navigation to javafx.fxml;
    exports io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage;
    opens io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage to javafx.fxml;
    exports io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents;
    opens io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents to javafx.fxml;
    exports io.github.guillermodubon.musicplayer.controllers.ui.components.inputs;
    opens io.github.guillermodubon.musicplayer.controllers.ui.components.inputs to javafx.fxml;
    exports io.github.guillermodubon.musicplayer.controllers.ui.screens.discoverPage;
    opens io.github.guillermodubon.musicplayer.controllers.ui.screens.discoverPage to javafx.fxml;
    exports io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage;
    opens io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage to javafx.fxml;
    exports io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.common;
    opens io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.common to javafx.fxml;
    exports io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.context;
    opens io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.context to javafx.fxml;
    exports io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.providers;
    opens io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.providers to javafx.fxml;
    exports io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.providers.base;
    opens io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.providers.base to javafx.fxml;
    exports io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.providers.common;
    opens io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.providers.common to javafx.fxml;
    exports io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.repositories;
    opens io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.repositories to javafx.fxml;
    exports io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage;
    opens io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage to javafx.fxml;
    exports io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage;
    opens io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage to javafx.fxml;
    exports io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.searchDropdown;
    opens io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.searchDropdown to javafx.fxml;
    exports io.github.guillermodubon.musicplayer.services.downloads.preferences;
    opens io.github.guillermodubon.musicplayer.services.downloads.preferences to javafx.fxml;
    exports io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems;
    opens io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems to javafx.fxml;
    exports io.github.guillermodubon.musicplayer.controllers.ui.components.downloadComponents;
    opens io.github.guillermodubon.musicplayer.controllers.ui.components.downloadComponents to javafx.fxml;
    exports io.github.guillermodubon.musicplayer.controllers.ui.components.downloadComponents.downloadCell;
    opens io.github.guillermodubon.musicplayer.controllers.ui.components.downloadComponents.downloadCell to javafx.fxml;
    exports io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar;
    opens io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar to javafx.fxml;
    exports io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.preview;
    opens io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.preview to javafx.fxml;
    exports io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.queuePane;
    opens io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.queuePane to javafx.fxml;
    exports io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu;
    opens io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu to javafx.fxml;
    exports io.github.guillermodubon.musicplayer.controllers.ui.screens.SplashScreen;
    opens io.github.guillermodubon.musicplayer.controllers.ui.screens.SplashScreen to javafx.fxml;

    opens io.github.guillermodubon.musicplayer.controllers.layout to javafx.fxml;
    exports io.github.guillermodubon.musicplayer.controllers.layout;

    opens io.github.guillermodubon.musicplayer.Views.layout to javafx.fxml;


    opens io.github.guillermodubon.musicplayer.Views.components.dialogs.base to javafx.fxml;


    // IMPORTANTE: para FXML (reflexión)
    opens io.github.guillermodubon.musicplayer.controllers.ui.screens.libraryCatalogListScreens to javafx.fxml;

    opens io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems.base to javafx.fxml;
}
