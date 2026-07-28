package io.github.guillermodubon.musicplayer.models;

import com.google.gson.JsonElement;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.SimpleObjectProperty;

/** Observable in-memory state for one cached Deezer resource. */
public final class DeezerCachedResource {
    private final ObjectProperty<JsonElement> value = new SimpleObjectProperty<>();
    private final ReadOnlyBooleanWrapper loading = new ReadOnlyBooleanWrapper(false);
    private volatile JsonElement latest;
    private volatile long loadedAtMillis;

    public JsonElement cached() {
        return latest;
    }

    public long loadedAtMillis() {
        return loadedAtMillis;
    }

    public ObjectProperty<JsonElement> valueProperty() {
        return value;
    }

    public ReadOnlyBooleanProperty loadingProperty() {
        return loading.getReadOnlyProperty();
    }

    public void setLoading(boolean state) {
        runOnFxThread(() -> loading.set(state));
    }

    public void publish(JsonElement element, long timestamp) {
        this.latest = element;
        this.loadedAtMillis = timestamp;
        runOnFxThread(() -> value.set(element));
    }

    private static void runOnFxThread(Runnable action) {
        if (action == null) return;
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }
}
