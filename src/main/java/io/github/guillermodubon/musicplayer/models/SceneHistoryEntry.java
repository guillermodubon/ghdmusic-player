package io.github.guillermodubon.musicplayer.models;

import javafx.scene.Parent;

import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Reusable navigation snapshot entry for back/forward history. */
public final class SceneHistoryEntry {
    private final Supplier<Parent> viewFactory;
    private final Map<String, Object> state;
    private final BiConsumer<Parent, Map<String, Object>> restoreAction;
    private final String controllerClassName;
    private final String navigationIdentity;

    public SceneHistoryEntry(
            Supplier<Parent> viewFactory,
            Map<String, Object> state,
            BiConsumer<Parent, Map<String, Object>> restoreAction,
            String controllerClassName
    ) {
        this(viewFactory, state, restoreAction, controllerClassName, null);
    }

    public SceneHistoryEntry(
            Supplier<Parent> viewFactory,
            Map<String, Object> state,
            BiConsumer<Parent, Map<String, Object>> restoreAction,
            String controllerClassName,
            String navigationIdentity
    ) {
        this.viewFactory = viewFactory;
        this.state = state;
        this.restoreAction = restoreAction;
        this.controllerClassName = controllerClassName;
        this.navigationIdentity = navigationIdentity;
    }

    public Parent createView() {
        try {
            return viewFactory == null ? null : viewFactory.get();
        } catch (Throwable ignored) {
            return null;
        }
    }

    public Map<String, Object> state() {
        return state;
    }

    public BiConsumer<Parent, Map<String, Object>> restoreAction() {
        return restoreAction;
    }

    public String controllerClassName() {
        return controllerClassName;
    }

    public String navigationIdentity() {
        return navigationIdentity;
    }
}
