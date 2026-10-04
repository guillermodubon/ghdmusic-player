package io.github.guillermodubon.musicplayer.services.downloads.bulk;

import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Set;

final class DeferredSongIndexQueue {

    private final PriorityQueue<Integer> orderedIndexes = new PriorityQueue<>();
    private final Set<Integer> queuedIndexes = new HashSet<>();

    synchronized boolean offer(int songIndex) {
        if (songIndex < 0 || !queuedIndexes.add(songIndex)) return false;
        orderedIndexes.add(songIndex);
        return true;
    }

    synchronized Integer poll() {
        Integer index = orderedIndexes.poll();
        if (index != null) queuedIndexes.remove(index);
        return index;
    }

    synchronized int size() {
        return orderedIndexes.size();
    }

    synchronized boolean isEmpty() {
        return orderedIndexes.isEmpty();
    }

    synchronized void clear() {
        orderedIndexes.clear();
        queuedIndexes.clear();
    }
}
