package io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.searchDropdown;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.searchDropdown.context.SearchDropdownContext;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.searchDropdown.helpers.SearchDropdownKeyboardCoordinator;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.searchDropdown.helpers.SearchDropdownPopupRenderer;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.searchDropdown.helpers.SearchDropdownSearchCoordinator;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.searchDropdown.repositories.SearchDropdownDeezerRepository;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.searchDropdown.services.SearchDropdownService;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.SearchResultsPageController;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.ArtistCardActionManager;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.models.SearchCandidate;
import io.github.guillermodubon.musicplayer.services.navigation.SceneStateFlowManager;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import io.github.guillermodubon.musicplayer.utils.DeezerEndpoints;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/**
 * FXML facade for the global search dropdown.
 * Search execution, popup rendering and keyboard selection are delegated to helpers.
 */
public class SearchDropdownController {

    // Cards only. The renderer appends the view-all action separately.
    private static final int MAX_RESULTS = 15;

    @FXML private VBox root;
    @FXML private VBox searchResultsBox;
    @FXML private ScrollPane resultsScrollPane;

    private final Popup searchPopup = new Popup();
    private SearchDropdownKeyboardCoordinator keyboardCoordinator;
    private SearchDropdownPopupRenderer popupRenderer;
    private SearchDropdownSearchCoordinator searchCoordinator;

    private TextField boundField;
    private Consumer<String> boundOnFilterChanged;
    private MusicCardActionManager boundMusicActions;
    private ArtistCardActionManager boundArtistActions;
    private StartUpService startUpServiceForResults;

    @FXML
    private void initialize() {
        if (root != null) {
            root.getStyleClass().add("search-dropdown-root");
        }
        if (searchResultsBox != null) {
            searchResultsBox.getStyleClass().add("search-dropdown-results");
        }
        if (resultsScrollPane != null) {
            resultsScrollPane.getStyleClass().add("search-dropdown-scroll");
        }

        keyboardCoordinator = new SearchDropdownKeyboardCoordinator(
                searchPopup,
                searchResultsBox,
                resultsScrollPane,
                this::openFullResultsForCurrentQuery,
                this::showLatestCandidates,
                () -> searchCoordinator != null
                        && !searchCoordinator.latestCandidates().isEmpty()
        );
        installDropdownKeyboardNavigation();
    }

    public void bindToTextField(
            TextField searchField,
            Consumer<String> onFilterChanged,
            MusicCardActionManager musicActions,
            ArtistCardActionManager artistActions,
            StartUpService startUpService
    ) {
        this.boundField = searchField;
        this.boundOnFilterChanged = onFilterChanged;
        this.boundMusicActions = musicActions;
        this.boundArtistActions = artistActions;
        this.startUpServiceForResults = startUpService;

        DeezerEndpoints.SearchDropdownEndpoints endpoints =
                DeezerEndpoints.defaultSearchDropdownEndpoints();
        SearchDropdownDeezerRepository deezerRepository =
                new SearchDropdownDeezerRepository(endpoints);
        SearchDropdownContext context = new SearchDropdownContext(
                startUpService,
                deezerRepository,
                endpoints,
                musicActions,
                artistActions
        );
        SearchDropdownService service = new SearchDropdownService(context);

        popupRenderer = new SearchDropdownPopupRenderer(
                root,
                searchResultsBox,
                resultsScrollPane,
                searchPopup,
                keyboardCoordinator,
                service,
                musicActions,
                artistActions,
                this::openSearchResultsScene
        );
        popupRenderer.setBoundField(searchField);

        searchCoordinator = new SearchDropdownSearchCoordinator(
                popupRenderer::clearRenderedResults,
                searchPopup::hide,
                popupRenderer::cancelImageLoads,
                (query, token) -> {
                    if (searchCoordinator != null
                            && searchCoordinator.isSearchCurrent(query, token)) {
                        popupRenderer.showLoadingInPopup(query, token);
                    }
                },
                popupRenderer::showCandidatesInPopup,
                MAX_RESULTS
        );
        searchCoordinator.setSearchService(service);

        searchPopup.getContent().clear();
        searchPopup.getContent().add(root);
        searchPopup.setAutoHide(true);
        searchPopup.setHideOnEscape(true);

        installKeyboardNavigation(searchField);
        installDropdownKeyboardNavigation();
        installSearchFieldListeners(searchField);
    }

    private void installSearchFieldListeners(TextField searchField) {
        searchField.textProperty().addListener((obs, oldRaw, newRaw) -> {
            String newText = newRaw == null ? "" : newRaw.trim();
            if ((oldRaw == null || !oldRaw.trim().isEmpty()) && newText.isEmpty()) {
                clearSearchInput();
                return;
            }
            searchCoordinator.handleInput(newText);
        });

        searchField.setOnAction(event -> {
            String query = searchField.getText() == null
                    ? ""
                    : searchField.getText().trim();
            if (query.isBlank()) {
                clearSearchInput();
            } else {
                openSearchResultsScene(query);
                Platform.runLater(searchPopup::hide);
            }
        });

        searchField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (isFocused) {
                String currentText = searchField.getText() == null
                        ? ""
                        : searchField.getText().trim();
                if (currentText.isEmpty()) {
                    return;
                }
                if (searchCoordinator.hasLatestCandidatesFor(currentText)) {
                    Platform.runLater(this::showLatestCandidates);
                } else {
                    searchCoordinator.handleInput(currentText);
                }
            } else {
                Platform.runLater(searchPopup::hide);
            }
        });
    }

    private void clearSearchInput() {
        if (searchCoordinator != null) {
            searchCoordinator.clearInputState();
        }
        if (boundOnFilterChanged != null) {
            try {
                boundOnFilterChanged.accept("");
            } catch (Exception ignored) {
            }
        }
        if (searchCoordinator != null) {
            searchCoordinator.cancelAndClearResults();
        }
        Platform.runLater(searchPopup::hide);
    }

    private void installKeyboardNavigation(TextField searchField) {
        if (keyboardCoordinator != null) {
            keyboardCoordinator.installOn(searchField);
        }
    }

    private void installDropdownKeyboardNavigation() {
        if (keyboardCoordinator == null) {
            return;
        }
        keyboardCoordinator.installOn(root);
        keyboardCoordinator.installOn(searchResultsBox);
        keyboardCoordinator.installOn(resultsScrollPane);
    }

    private void showLatestCandidates() {
        if (searchCoordinator == null
                || popupRenderer == null
                || searchCoordinator.latestCandidates().isEmpty()) {
            return;
        }

        List<SearchCandidate> candidates = searchCoordinator.latestCandidates();
        String query = searchCoordinator.latestQuery();
        if (query != null) {
            Runnable render = () -> popupRenderer.showCandidatesInPopup(candidates, query);
            if (Platform.isFxApplicationThread()) {
                render.run();
            } else {
                Platform.runLater(render);
            }
        }
    }

    private void openFullResultsForCurrentQuery() {
        String query = boundField == null || boundField.getText() == null
                ? ""
                : boundField.getText().trim();
        if (query.isBlank()) {
            return;
        }
        openSearchResultsScene(query);
        Platform.runLater(searchPopup::hide);
    }

    private void openSearchResultsScene(String query) {
        Parent rootNode = createSearchResultsView(query);
        if (rootNode == null) {
            return;
        }
        Platform.runLater(() -> SceneStateFlowManager.getInstance()
                .navigateToAndPushCurrent(rootNode, null, null));
    }

    private Parent createSearchResultsView(String query) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(
                    "/io/github/guillermodubon/musicplayer/Views/screens/searchResultsPage/SearchResultsPage.fxml"
            ));
            Parent rootNode = loader.load();
            Object controller = loader.getController();
            if (rootNode != null && controller != null) {
                rootNode.getProperties().put("controller", controller);
            }

            if (controller instanceof SearchResultsPageController resultsController) {
                resultsController.initAndSearch(
                        startUpServiceForResults,
                        boundMusicActions,
                        boundArtistActions,
                        query
                );
            }

            SceneStateFlowManager.attachNavigationFactory(
                    rootNode,
                    () -> createSearchResultsView(query)
            );
            return rootNode;
        } catch (IOException error) {
            error.printStackTrace();
            return null;
        }
    }
}
