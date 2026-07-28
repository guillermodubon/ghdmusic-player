package io.github.guillermodubon.musicplayer.models;

import java.util.Objects;

/**
 * Representa una entrada del historial de reproduccion.
 */
public class PlaybackHistory {
    private Long historyId;
    private long itemId;
    private String itemType;
    private String name;
    private long playedAt;

    public PlaybackHistory() {}

    public PlaybackHistory(Long historyId, long itemId, String name, long playedAt) {
        this(historyId, itemId, "ALBUM", name, playedAt);
    }

    public PlaybackHistory(Long historyId, long itemId, String itemType, String name, long playedAt) {
        this.historyId = historyId;
        this.itemId = itemId;
        this.itemType = normalizeType(itemType);
        this.name = name;
        this.playedAt = playedAt;
    }

    public PlaybackHistory(long itemId, String name, long playedAt) {
        this(null, itemId, name, playedAt);
    }

    public PlaybackHistory(long itemId, String itemType, String name, long playedAt) {
        this(null, itemId, itemType, name, playedAt);
    }

    public Long getHistoryId() { return historyId; }
    public void setHistoryId(Long historyId) { this.historyId = historyId; }

    public long getItemId() { return itemId; }
    public void setItemId(long itemId) { this.itemId = itemId; }

    public String getItemType() { return itemType == null || itemType.isBlank() ? "ALBUM" : itemType; }
    public void setItemType(String itemType) { this.itemType = normalizeType(itemType); }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public long getPlayedAt() { return playedAt; }
    public void setPlayedAt(long playedAt) { this.playedAt = playedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        PlaybackHistory that = (PlaybackHistory) o;
        return itemId == that.itemId &&
                playedAt == that.playedAt &&
                Objects.equals(historyId, that.historyId) &&
                Objects.equals(itemType, that.itemType) &&
                Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(historyId, itemId, itemType, name, playedAt);
    }

    @Override
    public String toString() {
        return "PlaybackHistory{" +
                "historyId=" + historyId +
                ", itemId=" + itemId +
                ", itemType='" + itemType + '\'' +
                ", name='" + name + '\'' +
                ", playedAt=" + playedAt +
                '}';
    }

    private static String normalizeType(String raw) {
        if (raw == null || raw.isBlank()) return "ALBUM";
        String normalized = raw.trim().toUpperCase();
        return switch (normalized) {
            case "PLAYLIST", "SINGLE", "ALBUM" -> normalized;
            case "EPISODE" -> "ALBUM";
            default -> "ALBUM";
        };
    }
}
