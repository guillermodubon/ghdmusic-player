package io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.queuePane.services;

import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.playback.PlaybackManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class QueueSidebarContentService {

    public String buildNextFromLabel(PlaybackManager pm) {
        if (pm == null) return "Next from:";
        String origin = pm.getOriginSource();
        if (origin == null || origin.isBlank()) return "Next from:";
        return "Next from: " + origin;
    }


    public List<Song> getQueueSongs(PlaybackManager pm) {
        if (pm == null) return List.of();
        return new ArrayList<>(pm.getQueue());
    }

    public List<Song> getVisibleRemainder(PlaybackManager pm) {
        if (pm == null) return List.of();

        List<Song> remainder = new ArrayList<>(pm.getRemainder());
        List<Song> source = pm.getSourceSongList();
        Song current = pm.getCurrentSong();
        Set<Song> sourceSongs = source == null ? null : new HashSet<>(source);

        return remainder.stream()
                .filter(Objects::nonNull)
                .filter(s -> sourceSongs == null || sourceSongs.contains(s))
                .filter(s -> current == null || s.getSongID() != current.getSongID())
                .toList();
    }
}
