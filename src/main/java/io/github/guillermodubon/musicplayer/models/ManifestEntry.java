package io.github.guillermodubon.musicplayer.models;

/** A single persisted entry from the user's local media manifest. */
public class ManifestEntry {
    public long deezerId;
    public long lastModified;

    public ManifestEntry(long deezerId, long lastModified) {
        this.deezerId = deezerId;
        this.lastModified = lastModified;
    }

    public long getDeezerId() {
        return deezerId;
    }

    public long getLastModified() {
        return lastModified;
    }
}
