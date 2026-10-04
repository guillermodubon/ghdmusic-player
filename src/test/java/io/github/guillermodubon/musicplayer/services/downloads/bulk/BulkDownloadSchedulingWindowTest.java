package io.github.guillermodubon.musicplayer.services.downloads.bulk;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BulkDownloadSchedulingWindowTest {

    @Test
    void refillsAReleasedProviderPhaseWithoutWaitingForTheOtherFour() {
        int workerCapacity = 8;
        int providerLimit = 5;
        int activeTasks = 5;
        int activeProviderPhases = 5;

        activeProviderPhases--;
        int replacementSlots = BulkDownloadSchedulingWindow.availableSlots(
                activeTasks,
                activeProviderPhases,
                workerCapacity,
                providerLimit
        );

        assertEquals(1, replacementSlots);
        activeTasks += replacementSlots;
        activeProviderPhases += replacementSlots;
        assertEquals(6, activeTasks);
        assertEquals(5, activeProviderPhases);
    }

    @Test
    void startupAndThroughputSimulationStayBoundedForLargeSources() {
        for (int sourceSize : List.of(5, 20, 500, 1_000, 10_000)) {
            Simulation result = simulate(sourceSize, 8, 5);
            assertEquals(Math.min(sourceSize, 5), result.startupTasks());
            assertEquals(sourceSize, result.createdTasks());
            assertEquals(sourceSize, result.completedTasks());
            assertTrue(result.maximumActiveTasks() <= 8);
            assertTrue(result.maximumProviderPhases() <= 5);
        }
    }

    @Test
    void schedulingPreservesSourceOrderAndNeverCreatesRigidGroups() {
        Queue<Integer> sourceIndexes = new ArrayDeque<>();
        for (int index = 0; index < 1_000; index++) sourceIndexes.add(index);
        List<Integer> started = new ArrayList<>();
        int activeTasks = 0;
        int activeProviderPhases = 0;

        int initialSlots = BulkDownloadSchedulingWindow.availableSlots(
                activeTasks, activeProviderPhases, 8, 5);
        for (int count = 0; count < initialSlots; count++) {
            started.add(sourceIndexes.remove());
            activeTasks++;
            activeProviderPhases++;
        }
        assertEquals(List.of(0, 1, 2, 3, 4), started);

        activeProviderPhases--;
        int continuousRefill = BulkDownloadSchedulingWindow.availableSlots(
                activeTasks, activeProviderPhases, 8, 5);
        assertEquals(1, continuousRefill);
        started.add(sourceIndexes.remove());
        activeTasks++;
        activeProviderPhases++;
        assertEquals(5, activeProviderPhases);
        assertEquals(5, started.get(started.size() - 1));
    }

    private Simulation simulate(int sourceSize, int workerCapacity, int providerLimit) {
        int nextIndex = 0;
        int activeTasks = 0;
        int activeProviderPhases = 0;
        int createdTasks = 0;
        int completedTasks = 0;
        int maximumActiveTasks = 0;
        int maximumProviderPhases = 0;
        int startupTasks = 0;

        while (completedTasks < sourceSize) {
            int available = Math.min(
                    BulkDownloadSchedulingWindow.availableSlots(
                            activeTasks,
                            activeProviderPhases,
                            workerCapacity,
                            providerLimit
                    ),
                    sourceSize - nextIndex
            );
            for (int index = 0; index < available; index++) {
                nextIndex++;
                createdTasks++;
                activeTasks++;
                activeProviderPhases++;
            }
            if (completedTasks == 0) startupTasks = createdTasks;
            maximumActiveTasks = Math.max(maximumActiveTasks, activeTasks);
            maximumProviderPhases = Math.max(maximumProviderPhases, activeProviderPhases);

            if (activeTasks > 0) {
                activeTasks--;
                activeProviderPhases--;
                completedTasks++;
            }
        }
        return new Simulation(startupTasks, createdTasks, completedTasks,
                maximumActiveTasks, maximumProviderPhases);
    }

    private record Simulation(int startupTasks,
                              int createdTasks,
                              int completedTasks,
                              int maximumActiveTasks,
                              int maximumProviderPhases) {
    }
}
