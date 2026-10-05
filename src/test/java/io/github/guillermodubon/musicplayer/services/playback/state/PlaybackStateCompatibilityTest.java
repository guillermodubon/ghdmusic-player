package io.github.guillermodubon.musicplayer.services.playback.state;

import io.github.guillermodubon.musicplayer.models.Song;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackStateCompatibilityTest {

    @Test
    void reordersQueueAndSourceRemainderWithoutMovingTheCurrentSong() {
        Song current = song(1, true);
        Song first = song(2, true);
        Song second = song(3, true);
        Song queued = song(4, true);
        PlaybackState state = new PlaybackState();
        state.setSourceSongList(List.of(current, first, second));
        state.setCurrentSongList(List.of(current, first, second));
        state.setCurrentIndex(0);
        state.enqueueLast(queued);

        assertFalse(state.replaceQueueOrder(List.of(queued), List.of(queued)));
        state.enqueueLast(song(5, true));
        Song fifth = state.getQueueCopy().getLast();
        assertTrue(state.replaceQueueOrder(List.of(queued, fifth), List.of(fifth, queued)));
        assertTrue(state.replaceCurrentRemainderOrder(List.of(first, second), List.of(second, first)));

        assertEquals(current, state.getCurrentSongAtCurrentIndex());
        assertEquals(List.of(current, second, first), state.getCurrentSongListCopy());
        assertEquals(List.of(current, second, first), state.getSourceSongListCopy());
        assertEquals(List.of(fifth, queued), List.copyOf(state.getQueueCopy()));
    }

    @Test
    void completedDownloadReplacesCanonicalReferencesWithoutChangingSourceOrder() {
        Song first = song(10, true);
        Song remote = song(11, false);
        Song last = song(12, true);
        Song downloaded = song(110, true);
        PlaybackState state = new PlaybackState();
        state.setSourceSongList(List.of(first, remote, last));
        state.setCurrentSongList(List.of(first, remote, last));
        state.enqueueLast(remote);

        state.replaceSongReferences(remote.getSongID(), downloaded);

        assertEquals(List.of(first, downloaded, last), state.getSourceSongListCopy());
        assertEquals(List.of(first, downloaded, last), state.getCurrentSongListCopy());
        assertEquals(downloaded, state.getQueueCopy().getFirst());
    }

    private static Song song(long id, boolean local) {
        return new Song(id, "Track " + id, List.of(), null, null, 1, local);
    }
}
