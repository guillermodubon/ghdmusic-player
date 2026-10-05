package io.github.guillermodubon.musicplayer.services.playback;

import io.github.guillermodubon.musicplayer.models.Song;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalPlaybackEligibilityTest {

    private final LocalPlaybackEligibility eligibility = new LocalPlaybackEligibility();

    @Test
    void sourceOrderKeepsSelectedMissingFileAndOnlyOtherPlayableLocalTracks() {
        Song before = song(1, true);
        Song selectedMissingFile = song(2, true);
        Song remote = song(3, false);
        Song after = song(4, true);
        Song duplicateAfter = song(4, true);

        List<Song> result = eligibility.buildLocalSource(
                List.of(before, selectedMissingFile, remote, after, duplicateAfter),
                selectedMissingFile,
                song -> song != before,
                (left, right) -> left.getSongID() == right.getSongID()
        );

        assertEquals(List.of(selectedMissingFile, after), result);
        assertTrue(eligibility.isLocalSong(selectedMissingFile));
    }

    @Test
    void collectionPlaybackRequiresAnImmediatelyPlayableLocalFile() {
        Song local = song(10, true);
        Song remote = song(11, false);

        assertEquals(List.of(local), eligibility.playableSongs(
                List.of(local, remote), song -> song == local
        ));
    }

    private Song song(long id, boolean local) {
        return new Song(id, "Track " + id, List.of(), null, null, 1, local);
    }
}
