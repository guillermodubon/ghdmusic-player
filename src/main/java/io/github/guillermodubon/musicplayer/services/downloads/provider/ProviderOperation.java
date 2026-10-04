package io.github.guillermodubon.musicplayer.services.downloads.provider;

public enum ProviderOperation {
    DOWNLOAD_SINGLE(true, false),
    DOWNLOAD_BULK(true, true),
    SEARCH(false, false),
    TITLE_LOOKUP(false, false),
    THUMBNAIL_LOOKUP(false, false),
    FORMAT_PROBE(false, false);

    private final boolean download;
    private final boolean bulk;

    ProviderOperation(boolean download, boolean bulk) {
        this.download = download;
        this.bulk = bulk;
    }

    public boolean isDownload() {
        return download;
    }

    public boolean isBulk() {
        return bulk;
    }
}
