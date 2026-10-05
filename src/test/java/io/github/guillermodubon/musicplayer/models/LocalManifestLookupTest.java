package io.github.guillermodubon.musicplayer.models;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalManifestLookupTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void verifiesLocalityFromFileEvidenceInsteadOfNumericIdOrTitle() throws Exception {
        Path audio = temporaryDirectory.resolve("Different filename.mp3");
        Files.writeString(audio, "audio fixture");
        ManifestEntry entry = new ManifestEntry(900, Files.getLastModifiedTime(audio).toMillis(),
                Files.size(audio), audio.getFileName().toString());
        LocalManifestLookup lookup = LocalManifestLookup.of(Map.of(audio.getFileName().toString(), entry));

        Song localWithUnrelatedNumericId = song(900, true, audio.toString());
        Song titleOnlyMatch = song(900, true, null);
        Song remote = song(900, false, audio.toString());

        assertTrue(lookup.matches(localWithUnrelatedNumericId));
        assertFalse(lookup.matches(titleOnlyMatch));
        assertFalse(lookup.matches(remote));
    }

    private Song song(long id, boolean local, String path) {
        return new Song(id, "A title unrelated to the file", List.of(), null, path, 1, local);
    }
}
