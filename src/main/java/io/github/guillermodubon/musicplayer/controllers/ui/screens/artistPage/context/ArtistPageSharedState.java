package io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.context;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class ArtistPageSharedState {
    private final Set<Long> localArtistIds = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean localHasCards = new AtomicBoolean(false);
    private final AtomicInteger generation = new AtomicInteger(0);

    public void setLocalHasCards(boolean value) { localHasCards.set(value); }

    public int generation() { return generation.get(); }
    public void setGeneration(int value) { generation.set(value); }
    public boolean isCurrent(int value) { return generation.get() == value; }

    public void clear() {
        localArtistIds.clear();
        localHasCards.set(false);
    }
}
