package io.github.guillermodubon.musicplayer.services.downloads.bulk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeferredSongIndexQueueTest {

    @Test
    void deferredSongsAreUniqueAndReturnedInOriginalOrder() {
        DeferredSongIndexQueue queue = new DeferredSongIndexQueue();

        assertTrue(queue.offer(7));
        assertTrue(queue.offer(3));
        assertFalse(queue.offer(7));
        assertEquals(2, queue.size());
        assertEquals(3, queue.poll());
        assertEquals(7, queue.poll());
        assertTrue(queue.offer(7));
        assertEquals(7, queue.poll());
        assertNull(queue.poll());
        assertTrue(queue.isEmpty());
    }

    @Test
    void requeuedSongIsReturnedBeforeUntouchedSongsWithoutCreatingDuplicates() {
        DeferredSongIndexQueue queue = new DeferredSongIndexQueue();

        assertTrue(queue.offer(2));
        assertFalse(queue.offer(2));
        assertEquals(2, queue.poll());
        assertTrue(queue.offer(2));
        assertFalse(queue.offer(2));
        assertEquals(2, queue.poll());
        assertNull(queue.poll());
    }
}
