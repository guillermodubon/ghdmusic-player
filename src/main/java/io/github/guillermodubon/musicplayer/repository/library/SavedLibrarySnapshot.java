package io.github.guillermodubon.musicplayer.repository.library;

import java.util.Set;

/** Batched saved-state view over the currently hydrated canonical entities. */
public record SavedLibrarySnapshot(Set<Long> directlySavedSongIds,
                                   Set<Long> savedReleaseIds,
                                   Set<Long> librarySongIds) {
    public SavedLibrarySnapshot {
        directlySavedSongIds = Set.copyOf(directlySavedSongIds);
        savedReleaseIds = Set.copyOf(savedReleaseIds);
        librarySongIds = Set.copyOf(librarySongIds);
    }

    public static SavedLibrarySnapshot empty() {
        return new SavedLibrarySnapshot(Set.of(), Set.of(), Set.of());
    }
}
