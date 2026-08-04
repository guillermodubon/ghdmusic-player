package io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data;

import javafx.scene.image.Image;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.function.Consumer;
import io.github.guillermodubon.musicplayer.utils.ArtistIdentity;

public record MusicCardData(
        String id,
        Image cover,
        String title,
        List<String> artists,
        Consumer<String> onPlay,
        Consumer<String> onArtistClick,
        boolean artistLinksEnabled
) {
    public static final String USER_PLAYLIST_CREATOR_LABEL = "By you";
    public static final String REMOTE_PLAYLIST_CREATOR_LABEL = "Custom Playlist";
    public static final String UNKNOWN_ARTIST_LABEL = "Unknown";
    public static final String VARIOUS_ARTISTS_LABEL = "Various Artists";
    public static final String LOCAL_FILE_ARTIST_LABEL = "Local file";

    public MusicCardData {
        title = title == null || title.isBlank() ? "Unknown" : title;
        artists = normalizeArtistLabels(artists, UNKNOWN_ARTIST_LABEL);
    }

    public MusicCardData(String id,
                         Image cover,
                         String title,
                         List<String> artists,
                         Consumer<String> onPlay,
                         Consumer<String> onArtistClick) {
        this(id, cover, title, artists, onPlay, onArtistClick, true);
    }

    public static MusicCardData playlist(String id,
                                         Image cover,
                                         String title,
                                         List<String> creators,
                                         Consumer<String> onPlay,
                                         Consumer<String> onArtistClick) {
        return new MusicCardData(
                id,
                cover,
                title,
                playlistCreators(creators),
                onPlay,
                onArtistClick,
                false
        );
    }

    public static MusicCardData localFile(String id,
                                          Image cover,
                                          String title,
                                          Consumer<String> onPlay) {
        return new MusicCardData(
                id,
                cover,
                title,
                List.of(LOCAL_FILE_ARTIST_LABEL),
                onPlay,
                null,
                false
        );
    }

    public static List<String> playlistCreators(List<String> creators) {
        return List.of(isUserPlaylistCreator(creators)
                ? USER_PLAYLIST_CREATOR_LABEL
                : REMOTE_PLAYLIST_CREATOR_LABEL);
    }

    public static String playlistCreatorLabel(String value) {
        return isUserPlaylistCreator(value)
                ? USER_PLAYLIST_CREATOR_LABEL
                : REMOTE_PLAYLIST_CREATOR_LABEL;
    }

    public static boolean isUserPlaylistCreator(List<String> creators) {
        if (creators == null || creators.isEmpty()) return false;
        for (String creator : creators) {
            if (isUserPlaylistCreator(creator)) return true;
        }
        return false;
    }

    public static boolean isUserPlaylistCreator(String value) {
        if (value == null || value.isBlank()) return false;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("user")
                || normalized.equals("by you")
                || normalized.equals("you");
    }

    public static List<String> normalizeArtistLabels(List<String> raw, String fallback) {
        if (raw == null || raw.isEmpty()) return List.of(fallback);

        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String value : raw) {
            if (value == null) continue;
            String trimmed = value.trim();
            if (trimmed.isBlank()) continue;
            out.add(ArtistIdentity.displayName(trimmed));
        }

        return out.isEmpty() ? List.of(fallback) : List.copyOf(out);
    }
}
