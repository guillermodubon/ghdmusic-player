package io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.common;

import io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.repositories.GenrePageMemoryRepository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class GenrePageSharedState {
    private final Set<Long> libraryArtistIds = ConcurrentHashMap.newKeySet();
    private final Set<Long> topArtistIds = ConcurrentHashMap.newKeySet();
    private final Map<Long, String> topArtistCandidates = new ConcurrentHashMap<>();
    private final AtomicBoolean libraryHasCards = new AtomicBoolean(false);
    // Null means the library section has not produced its snapshot yet.
    private volatile GenrePageMemoryRepository.LibrarySnapshot librarySnapshot;

    public Set<Long> libraryArtistIds() { return libraryArtistIds; }
    public Set<Long> topArtistIds() { return topArtistIds; }
    public Map<Long, String> topArtistCandidates() { return new LinkedHashMap<>(topArtistCandidates); }
    public boolean libraryHasCards() { return libraryHasCards.get(); }
    public GenrePageMemoryRepository.LibrarySnapshot librarySnapshot() { return librarySnapshot; }

    public void setLibraryHasCards(boolean value) { libraryHasCards.set(value); }
    public void setLibrarySnapshot(GenrePageMemoryRepository.LibrarySnapshot snapshot) {
        librarySnapshot = snapshot == null ? GenrePageMemoryRepository.LibrarySnapshot.empty() : snapshot;
    }
    public void setTopArtistCandidates(Map<Long, String> candidates) {
        topArtistCandidates.clear();
        if (candidates == null) return;
        candidates.forEach((id, name) -> {
            if (id != null && id > 0) topArtistCandidates.put(id, name == null ? "Unknown" : name);
        });
    }
    public void clear() {
        libraryArtistIds.clear();
        topArtistIds.clear();
        topArtistCandidates.clear();
        libraryHasCards.set(false);
        librarySnapshot = null;
    }
}
