package io.github.guillermodubon.musicplayer.services.navigation;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.Parent;
import javafx.scene.layout.BorderPane;
import io.github.guillermodubon.musicplayer.models.SceneHistoryEntry;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Maneja navegación con back/forward guardando una "snapshot" in-memory por pantalla.
 */

import java.lang.reflect.Method;

public class SceneStateFlowManager {

    private static final SceneStateFlowManager INSTANCE = new SceneStateFlowManager();
    public static final String NAVIGATION_FACTORY_KEY = "navigationFactory";
    public static final String SCREEN_KEY_PROPERTY = "screenKey";
    public static final String TRANSIENT_SCREEN_PROPERTY = "navigationTransient";
    public static final String NAVIGATION_IDENTITY_PROPERTY = "navigationIdentity";

    public static SceneStateFlowManager getInstance() {
        return INSTANCE;
    }

    private BorderPane rootPane;

    private final Deque<SceneHistoryEntry> backStack = new ArrayDeque<>();
    private final Deque<SceneHistoryEntry> forwardStack = new ArrayDeque<>();
    private final int MAX_HISTORY = 4;
    private final BooleanProperty canNavigateBack = new SimpleBooleanProperty(false);
    private final BooleanProperty canNavigateForward = new SimpleBooleanProperty(false);
    private final StringProperty currentScreenKey = new SimpleStringProperty(null);

    private SceneStateFlowManager() {}

    public static void attachNavigationFactory(Parent view, Supplier<Parent> factory) {
        if (view == null || factory == null) return;
        view.getProperties().put(NAVIGATION_FACTORY_KEY, factory);
    }

    public static void markTransient(Parent view, boolean transientView) {
        if (view == null) return;
        if (transientView) {
            view.getProperties().put(TRANSIENT_SCREEN_PROPERTY, Boolean.TRUE);
        } else {
            view.getProperties().remove(TRANSIENT_SCREEN_PROPERTY);
        }
    }

    public synchronized void setRoot(BorderPane root) {
        this.rootPane = root;
        backStack.clear();
        forwardStack.clear();
        updateCurrentScreenKey(root == null ? null : asParent(root.getCenter()));
        updateNavigationAvailability();
    }

    public ReadOnlyBooleanProperty canNavigateBackProperty() {
        return canNavigateBack;
    }

    public ReadOnlyBooleanProperty canNavigateForwardProperty() {
        return canNavigateForward;
    }

    public StringProperty currentScreenKeyProperty() {
        return currentScreenKey;
    }

    public synchronized String getCurrentScreenKey() {
        return currentScreenKey.get();
    }


    public synchronized void navigateToAndPushCurrent(Parent target,
                                                      Supplier<Map<String, Object>> captureCurrent,
                                                      BiConsumer<Parent, Map<String, Object>> restoreAction) {
        if (target == null) return;

        runOnFx(() -> {
            if (rootPane == null) return;

            Parent current = asParent(rootPane.getCenter());
            if (current != null && current != target && !isTransientScreen(current)) {
                Map<String, Object> snap = null;
                try {
                    if (captureCurrent != null) snap = safeGet(captureCurrent);
                    if (snap == null) snap = tryCaptureStateFromController(current);
                } catch (Exception ignored) {}

                String currentIdentity = navigationIdentity(current);
                if (!isDuplicateOfLast(current, snap, currentIdentity)) {
                    BiConsumer<Parent, Map<String, Object>> restore =
                            restoreAction != null ? restoreAction : createRestoreActionFromController(current);

                    Supplier<Parent> factory = navigationFactory(current);
                    if (factory != null) {
                        backStack.addLast(new SceneHistoryEntry(
                                factory,
                                snap,
                                restore,
                                controllerClassName(current),
                                currentIdentity
                        ));
                    }
                    while (backStack.size() > MAX_HISTORY) backStack.removeFirst();
                    forwardStack.clear();
                }
            }

            invokeOnDetached(current);
            rootPane.setCenter(target);
            updateCurrentScreenKey(target);
            updateNavigationAvailability();
        });
    }

    public synchronized boolean navigateBack() {
        if (backStack.isEmpty() || rootPane == null) return false;

        runOnFx(() -> {
            Parent current = asParent(rootPane.getCenter());

            Map<String, Object> curSnap = tryCaptureStateFromController(current);
            BiConsumer<Parent, Map<String, Object>> curRestore = createRestoreActionFromController(current);
            String currentIdentity = navigationIdentity(current);
            if (current != null && !isTransientScreen(current)
                    && !isDuplicateOfLast(current, curSnap, currentIdentity)) {
                Supplier<Parent> currentFactory = navigationFactory(current);
                if (currentFactory != null) {
                    forwardStack.addLast(new SceneHistoryEntry(
                            currentFactory,
                            curSnap,
                            curRestore,
                            controllerClassName(current),
                            currentIdentity
                    ));
                }
                while (forwardStack.size() > MAX_HISTORY) forwardStack.removeFirst();
            }

            SceneHistoryEntry prev = backStack.removeLast();
            Parent prevView = prev.createView();
            if (prevView == null) {
                updateNavigationAvailability();
                return;
            }

            invokeOnDetached(current);
            rootPane.setCenter(prevView);
            updateCurrentScreenKey(prevView);

            if (prev.restoreAction() != null) {
                safeRun(() -> prev.restoreAction().accept(prevView, prev.state()));
            } else {
                safeRun(() -> tryInvokeRestoreOnController(prevView, prev.state()));
            }
            updateNavigationAvailability();
        });

        return true;
    }

    public synchronized boolean navigateBackDiscardingCurrent() {
        if (backStack.isEmpty() || rootPane == null) return false;

        runOnFx(() -> {
            Parent current = asParent(rootPane.getCenter());
            SceneHistoryEntry prev = backStack.removeLast();
            Parent prevView = prev.createView();
            if (prevView == null) {
                updateNavigationAvailability();
                return;
            }

            invokeOnDetached(current);
            rootPane.setCenter(prevView);
            updateCurrentScreenKey(prevView);

            if (prev.restoreAction() != null) {
                safeRun(() -> prev.restoreAction().accept(prevView, prev.state()));
            } else {
                safeRun(() -> tryInvokeRestoreOnController(prevView, prev.state()));
            }
            updateNavigationAvailability();
        });

        return true;
    }

    public synchronized boolean navigateForward() {
        if (forwardStack.isEmpty() || rootPane == null) return false;

        runOnFx(() -> {
            Parent current = asParent(rootPane.getCenter());

            Map<String, Object> curSnap = tryCaptureStateFromController(current);
            BiConsumer<Parent, Map<String, Object>> curRestore = createRestoreActionFromController(current);
            String currentIdentity = navigationIdentity(current);
            if (current != null && !isTransientScreen(current)
                    && !isDuplicateOfLast(current, curSnap, currentIdentity)) {
                Supplier<Parent> currentFactory = navigationFactory(current);
                if (currentFactory != null) {
                    backStack.addLast(new SceneHistoryEntry(
                            currentFactory,
                            curSnap,
                            curRestore,
                            controllerClassName(current),
                            currentIdentity
                    ));
                }
                while (backStack.size() > MAX_HISTORY) backStack.removeFirst();
            }

            SceneHistoryEntry next = forwardStack.removeLast();
            Parent nextView = next.createView();
            if (nextView == null) {
                updateNavigationAvailability();
                return;
            }

            invokeOnDetached(current);
            rootPane.setCenter(nextView);
            updateCurrentScreenKey(nextView);

            if (next.restoreAction() != null) {
                safeRun(() -> next.restoreAction().accept(nextView, next.state()));
            } else {
                safeRun(() -> tryInvokeRestoreOnController(nextView, next.state()));
            }
            updateNavigationAvailability();
        });

        return true;
    }


    private void updateNavigationAvailability() {
        boolean hasBack = !backStack.isEmpty();
        boolean hasForward = !forwardStack.isEmpty();
        Runnable update = () -> {
            canNavigateBack.set(hasBack);
            canNavigateForward.set(hasForward);
        };
        if (Platform.isFxApplicationThread()) update.run();
        else Platform.runLater(update);
    }

    private void updateCurrentScreenKey(Parent view) {
        Object value = view == null ? null : view.getProperties().get(SCREEN_KEY_PROPERTY);
        String key = value instanceof String s && !s.isBlank() ? s : null;
        Runnable update = () -> currentScreenKey.set(key);
        if (Platform.isFxApplicationThread()) update.run();
        else Platform.runLater(update);
    }

    private boolean isDuplicateOfLast(Parent current, Map<String, Object> snap, String currentIdentity) {
        SceneHistoryEntry last = backStack.peekLast();
        if (last == null) return false;

        if (currentIdentity != null && !currentIdentity.isBlank()) {
            return Objects.equals(last.navigationIdentity(), currentIdentity);
        }

        String currClass = controllerClassName(current);
        if (!Objects.equals(last.controllerClassName(), currClass)) return false;

        Object lastTime = last.state() == null ? null : last.state().get("snapshotTime");
        Object currTime = snap == null ? null : snap.get("snapshotTime");

        if (lastTime != null || currTime != null) {
            return Objects.equals(lastTime, currTime);
        }

        return false;
    }

    private String navigationIdentity(Parent view) {
        if (view == null) return null;
        Object value = safeGet(() -> view.getProperties().get(NAVIGATION_IDENTITY_PROPERTY));
        return value instanceof String identity && !identity.isBlank() ? identity : null;
    }

    private boolean isTransientScreen(Parent view) {
        return view != null && Boolean.TRUE.equals(view.getProperties().get(TRANSIENT_SCREEN_PROPERTY));
    }

    private String controllerClassName(Parent p) {
        if (p == null) return null;
        Object ctrl = safeGet(() -> p.getProperties().get("controller"));
        return ctrl == null ? null : ctrl.getClass().getName();
    }

    @SuppressWarnings("unchecked")
    private Supplier<Parent> navigationFactory(Parent p) {
        if (p == null) return null;
        Object factory = safeGet(() -> p.getProperties().get(NAVIGATION_FACTORY_KEY));
        if (factory instanceof Supplier<?> supplier) {
            return () -> asParent(supplier.get());
        }
        return null;
    }

    private Parent asParent(Object o) {
        return (o instanceof Parent p) ? p : null;
    }

    private void invokeOnDetached(Parent p) {
        if (p == null) return;
        Object ctrl = safeGet(() -> p.getProperties().get("controller"));
        if (ctrl == null) return;

        safeRun(() -> {
            try {
                Method m = ctrl.getClass().getMethod("onDetached");
                m.setAccessible(true);
                m.invoke(ctrl);
            } catch (NoSuchMethodException ignored) {
            } catch (InvocationTargetException e) {
                throw new RuntimeException(e);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> tryCaptureStateFromController(Parent p) {
        if (p == null) return null;
        Object ctrl = safeGet(() -> p.getProperties().get("controller"));
        if (ctrl == null) return null;

        try {
            Method m = ctrl.getClass().getMethod("captureState");
            m.setAccessible(true);
            Object r = m.invoke(ctrl);
            if (r instanceof Map<?, ?> map) return (Map<String, Object>) map;
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void tryInvokeRestoreOnController(Parent p, Map<String, Object> state) {
        if (p == null) return;
        Object ctrl = safeGet(() -> p.getProperties().get("controller"));
        if (ctrl == null) return;

        try {
            Method m = ctrl.getClass().getMethod("restoreState", Map.class);
            m.setAccessible(true);
            m.invoke(ctrl, state);
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable ignored) {
        }
    }

    private BiConsumer<Parent, Map<String, Object>> createRestoreActionFromController(Parent p) {
        if (p == null) return null;
        Object ctrl = safeGet(() -> p.getProperties().get("controller"));
        if (ctrl == null) return null;
        return (parent, state) -> tryInvokeRestoreOnController(parent, state);
    }

    private void runOnFx(Runnable r) {
        if (Platform.isFxApplicationThread()) r.run();
        else Platform.runLater(r);
    }

    private static <T> T safeGet(Supplier<T> s) {
        try {
            return s == null ? null : s.get();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void safeRun(Runnable r) {
        try {
            if (r != null) r.run();
        } catch (Throwable ignored) {
        }
    }
}
