package io.github.guillermodubon.musicplayer.models;

/** A single persisted entry from the user's local media manifest. */
public class ManifestEntry {
    public long deezerId;
    public long lastModified;
    /** Cheap relocation fingerprint; it never stores or reads audio content. */
    public long fileSize;
    public String fileName;

    public ManifestEntry(long deezerId, long lastModified) {
        this(deezerId, lastModified, 0L, null);
    }

    public ManifestEntry(long deezerId, long lastModified, long fileSize, String fileName) {
        this.deezerId = deezerId;
        this.lastModified = lastModified;
        this.fileSize = Math.max(0L, fileSize);
        this.fileName = fileName;
    }

    public long getDeezerId() {
        return deezerId;
    }

    public long getLastModified() {
        return lastModified;
    }

    public long getFileSize() {
        return fileSize;
    }

    public String getFileName() {
        return fileName;
    }

    public ManifestEntry withLastModified(long value) {
        return new ManifestEntry(deezerId, value, fileSize, fileName);
    }
}
