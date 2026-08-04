package io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.searchDropdown.helpers;

import javafx.beans.value.ChangeListener;
import javafx.collections.ObservableList;
import javafx.css.PseudoClass;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Handles keyboard selection and hover feedback for search results. */
public final class SearchDropdownKeyboardCoordinator {

    private static final String INSTALLED_PROPERTY = "searchDropdownKeyboardInstalled";
    private static final String KEYBOARD_SELECTED_CLASS = "search-dropdown-keyboard-selected";
    private static final String KEYBOARD_SELECTED_BODY_CLASS = "search-dropdown-keyboard-selected-body";
    private static final String CARD_HOVER_CLASS = "search-result-card-hover";
    private static final String BODY_HOVER_CLASS = "search-result-card-body-hover";
    private static final String ORIGINAL_STYLE_KEY = "searchDropdownOriginalStyle";
    private static final String KEYBOARD_BODY_INLINE_STYLE =
            "-fx-background-color: #222222; -fx-border-color: transparent;";
    private static final PseudoClass KEYBOARD_SELECTED_PSEUDO =
            PseudoClass.getPseudoClass("keyboard-selected");

    private final Popup popup;
    private final VBox searchResultsBox;
    private final ScrollPane resultsScrollPane;
    private final Runnable openFullResults;
    private final Runnable showLatestCandidates;
    private final BooleanSupplier hasLatestCandidates;

    private final List<KeyboardResult> keyboardResults = new ArrayList<>();
    private int selectedResultIndex = -1;
    private boolean keyboardNavigationActive;

    public SearchDropdownKeyboardCoordinator(
            Popup popup,
            VBox searchResultsBox,
            ScrollPane resultsScrollPane,
            Runnable openFullResults,
            Runnable showLatestCandidates,
            BooleanSupplier hasLatestCandidates
    ) {
        this.popup = popup;
        this.searchResultsBox = searchResultsBox;
        this.resultsScrollPane = resultsScrollPane;
        this.openFullResults = openFullResults;
        this.showLatestCandidates = showLatestCandidates;
        this.hasLatestCandidates = hasLatestCandidates;
    }

    public void installOn(Node node) {
        if (node == null
                || Boolean.TRUE.equals(node.getProperties().get(INSTALLED_PROPERTY))) {
            return;
        }
        node.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyboardNavigation);
        node.getProperties().put(INSTALLED_PROPERTY, Boolean.TRUE);
    }

    public void installOnPopupScene(Node root) {
        if (root == null || root.getScene() == null) {
            return;
        }

        Scene popupScene = root.getScene();
        if (Boolean.TRUE.equals(popupScene.getProperties().get(INSTALLED_PROPERTY))) {
            return;
        }
        popupScene.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyboardNavigation);
        popupScene.getProperties().put(INSTALLED_PROPERTY, Boolean.TRUE);
    }

    public void reset() {
        clearKeyboardSelection();
        keyboardResults.clear();
        selectedResultIndex = -1;
        keyboardNavigationActive = false;
    }

    public void register(Node row, Runnable action) {
        if (row == null) {
            return;
        }
        row.setFocusTraversable(false);
        keyboardResults.add(new KeyboardResult(
                row,
                action,
                resolveKeyboardVisualNodes(row)
        ));
    }

    private void handleKeyboardNavigation(KeyEvent event) {
        if (event == null || event.isConsumed()) {
            return;
        }

        KeyCode code = event.getCode();
        if (code != KeyCode.DOWN && code != KeyCode.UP && code != KeyCode.ENTER) {
            return;
        }

        if (!popup.isShowing()) {
            if (code == KeyCode.ENTER) {
                openFullResults.run();
                event.consume();
            } else if (hasLatestCandidates.getAsBoolean()) {
                showLatestCandidates.run();
                event.consume();
            }
            return;
        }

        if (code == KeyCode.DOWN) {
            moveSelection(1);
            event.consume();
        } else if (code == KeyCode.UP) {
            moveSelection(-1);
            event.consume();
        } else if (code == KeyCode.ENTER) {
            if (keyboardNavigationActive && selectedResultIndex >= 0) {
                activateSelectedResult();
            } else {
                openFullResults.run();
            }
            event.consume();
        }
    }

    private void moveSelection(int delta) {
        if (keyboardResults.isEmpty()) {
            return;
        }

        int next;
        if (selectedResultIndex < 0) {
            next = delta < 0 ? keyboardResults.size() - 1 : 0;
        } else {
            next = Math.max(
                    0,
                    Math.min(keyboardResults.size() - 1, selectedResultIndex + delta)
            );
        }
        setSelection(next);
    }

    private void setSelection(int index) {
        if (index < 0 || index >= keyboardResults.size()) {
            return;
        }

        keyboardNavigationActive = true;
        if (selectedResultIndex == index) {
            scrollIntoView(keyboardResults.get(index).node());
            return;
        }

        clearKeyboardSelection();
        selectedResultIndex = index;
        KeyboardResult selected = keyboardResults.get(index);
        applySelectionStyle(selected, true);
        scrollIntoView(selected.node());
    }

    private void activateSelectedResult() {
        if (selectedResultIndex < 0 || selectedResultIndex >= keyboardResults.size()) {
            return;
        }

        Runnable action = keyboardResults.get(selectedResultIndex).action();
        if (action != null) {
            action.run();
        }
        javafx.application.Platform.runLater(popup::hide);
    }

    private void clearKeyboardSelection() {
        if (selectedResultIndex >= 0 && selectedResultIndex < keyboardResults.size()) {
            applySelectionStyle(keyboardResults.get(selectedResultIndex), false);
        }
    }

    private void applySelectionStyle(KeyboardResult result, boolean selected) {
        if (result == null || result.node() == null) {
            return;
        }

        Node node = result.node();
        node.pseudoClassStateChanged(KEYBOARD_SELECTED_PSEUDO, selected);
        setStyleClass(node, KEYBOARD_SELECTED_CLASS, selected);
        if (!(node instanceof Button)) {
            setStyleClass(node, CARD_HOVER_CLASS, selected);
        }

        for (Node visualNode : result.visualNodes()) {
            visualNode.pseudoClassStateChanged(KEYBOARD_SELECTED_PSEUDO, selected);
            setStyleClass(visualNode, KEYBOARD_SELECTED_BODY_CLASS, selected);
            setStyleClass(visualNode, BODY_HOVER_CLASS, selected);
            applyInlineSelectionStyle(visualNode, selected);
        }
    }

    private List<Node> resolveKeyboardVisualNodes(Node row) {
        if (row == null || row instanceof Button) {
            return List.of();
        }

        List<Node> visualNodes = new ArrayList<>();
        Deque<Node> pending = new ArrayDeque<>();
        pending.push(row);
        while (!pending.isEmpty()) {
            Node current = pending.pop();
            if (isSearchCardBody(current)) {
                visualNodes.add(current);
            }
            if (current instanceof Parent parent) {
                for (Node child : parent.getChildrenUnmodifiable()) {
                    pending.push(child);
                }
            }
        }

        if (visualNodes.isEmpty()) {
            visualNodes.add(row);
        }
        return visualNodes;
    }

    private boolean isSearchCardBody(Node node) {
        return node != null
                && (node.getStyleClass().contains("search-result-card-body")
                || node.getStyleClass().contains("search-artist-body")
                || node.getStyleClass().contains("search-music-body"));
    }

    private void applyInlineSelectionStyle(Node node, boolean selected) {
        if (node == null) {
            return;
        }

        if (selected) {
            node.getProperties().putIfAbsent(
                    ORIGINAL_STYLE_KEY,
                    node.getStyle() == null ? "" : node.getStyle()
            );
            String original = String.valueOf(node.getProperties().get(ORIGINAL_STYLE_KEY));
            node.setStyle(appendStyle(original, KEYBOARD_BODY_INLINE_STYLE));
            return;
        }

        Object original = node.getProperties().remove(ORIGINAL_STYLE_KEY);
        if (original != null) {
            node.setStyle(original.toString());
        }
    }

    private String appendStyle(String base, String addition) {
        String safeBase = base == null ? "" : base.trim();
        if (safeBase.isEmpty()) {
            return addition;
        }
        return safeBase.endsWith(";")
                ? safeBase + " " + addition
                : safeBase + "; " + addition;
    }

    private void setStyleClass(Node node, String styleClass, boolean present) {
        if (node == null || styleClass == null || styleClass.isBlank()) {
            return;
        }

        ObservableList<String> styleClasses = node.getStyleClass();
        if (present) {
            if (!styleClasses.contains(styleClass)) {
                styleClasses.add(styleClass);
            }
        } else {
            styleClasses.remove(styleClass);
        }
    }

    private void scrollIntoView(Node row) {
        if (row == null || resultsScrollPane == null || searchResultsBox == null) {
            return;
        }

        try {
            Bounds rowBounds = row.getBoundsInParent();
            double viewportHeight = resultsScrollPane.getViewportBounds().getHeight();
            double contentHeight = searchResultsBox.getBoundsInLocal().getHeight();
            double scrollableHeight = contentHeight - viewportHeight;
            if (scrollableHeight <= 0 || rowBounds == null) {
                return;
            }

            double currentTop = resultsScrollPane.getVvalue() * scrollableHeight;
            double currentBottom = currentTop + viewportHeight;
            double targetTop = rowBounds.getMinY();
            double targetBottom = rowBounds.getMaxY();
            double nextTop = currentTop;

            if (targetTop < currentTop) {
                nextTop = targetTop;
            } else if (targetBottom > currentBottom) {
                nextTop = targetBottom - viewportHeight;
            }

            resultsScrollPane.setVvalue(
                    Math.max(0.0, Math.min(1.0, nextTop / scrollableHeight))
            );
        } catch (Exception ignored) {
        }
    }

    private record KeyboardResult(Node node, Runnable action, List<Node> visualNodes) {
    }
}
