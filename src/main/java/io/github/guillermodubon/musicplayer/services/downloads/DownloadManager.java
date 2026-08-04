package io.github.guillermodubon.musicplayer.services.downloads;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import io.github.guillermodubon.musicplayer.controllers.ui.components.downloadComponents.DownloadSidebarMenuController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.queuePane.QueueController;
import io.github.guillermodubon.musicplayer.services.downloads.logging.DownloadLog;

import java.io.File;
import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public class DownloadManager {

    private static DownloadManager instance;

    private DownloadSidebarMenuController sidebarController;

    private final ObservableList<DownloadTask> tasks = FXCollections.observableArrayList();
    private final ExecutorService executor;
    private final Queue<DownloadTask> deferredTasks = new ConcurrentLinkedQueue<>();
    private final int workerCount;
    private volatile String exclusiveSessionId;

    private DownloadManager() {
        workerCount = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
        executor = Executors.newFixedThreadPool(
                workerCount,
                new DaemonThreadFactory()
        );
        DownloadLog.info("DownloadManager", "Initialized download executor with " + workerCount + " workers");
    }

    public static synchronized DownloadManager getInstance() {
        if (instance == null) instance = new DownloadManager();
        return instance;
    }

    public ObservableList<DownloadTask> getTasks() {
        return tasks;
    }

    public int getWorkerCount() {
        return workerCount;
    }


    public boolean hasExclusiveSession() {
        String active = exclusiveSessionId;
        return active != null && !active.isBlank();
    }

    public synchronized void setSidebarController(DownloadSidebarMenuController ctrl) {
        this.sidebarController = ctrl;
    }

    public synchronized DownloadSidebarMenuController getSidebarController() {
        return sidebarController;
    }

    public boolean enqueueTask(DownloadTask task) {
        if (task == null) return false;

        if (hasExistingTask(task.getQuery(), task.getTargetDir(), task.getCleanSongName())) {
            DownloadLog.warn("DownloadManager", "Ignored duplicate " + DownloadLog.taskLabel(task.getContext()));
            return false;
        }

        DownloadLog.info("DownloadManager", "Enqueued " + DownloadLog.taskLabel(task.getContext()));
        addTaskToUi(task);

        if (shouldDefer(task)) {
            task.setDeferredByExclusiveSession(true);
            deferredTasks.add(task);
            DownloadLog.info("DownloadManager", "Deferred task until exclusive session finishes: "
                    + DownloadLog.taskLabel(task.getContext()));
        } else {
            task.setDeferredByExclusiveSession(false);
            executor.submit(task);
        }
        return true;
    }

    private void addTaskToUi(DownloadTask task) {
        Runnable add = () -> insertTaskInUiOrder(task);
        if (Platform.isFxApplicationThread()) {
            add.run();
        } else {
            Platform.runLater(add);
        }
    }

    private void insertTaskInUiOrder(DownloadTask task) {
        if (task == null) return;
        int sessionTail = findLastTaskIndexForSession(task);
        if (sessionTail >= 0) {
            tasks.add(Math.min(sessionTail + 1, tasks.size()), task);
            return;
        }
        tasks.add(task);
    }

    private int findLastTaskIndexForSession(DownloadTask task) {
        if (task == null || task.getContext() == null) return -1;
        String sessionId = task.getContext().getBulkSessionId();
        if (sessionId == null || sessionId.isBlank()) return -1;

        for (int i = tasks.size() - 1; i >= 0; i--) {
            DownloadTask candidate = tasks.get(i);
            if (candidate == null || candidate.getContext() == null) continue;
            if (sessionId.equals(candidate.getContext().getBulkSessionId())) return i;
        }
        return -1;
    }

    public synchronized void beginExclusiveSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        exclusiveSessionId = sessionId;
        DownloadLog.info("DownloadManager", "Exclusive session started: " + sessionId);
    }

    public synchronized void endExclusiveSession(String sessionId) {
        if (exclusiveSessionId == null) return;
        if (sessionId != null && !sessionId.equals(exclusiveSessionId)) return;

        DownloadLog.info("DownloadManager", "Exclusive session ended: " + exclusiveSessionId);
        exclusiveSessionId = null;
        drainDeferredTasks();
    }

    private boolean shouldDefer(DownloadTask task) {
        String active = exclusiveSessionId;
        if (active == null || active.isBlank()) return false;
        String taskSession = task == null || task.getContext() == null ? null : task.getContext().getBulkSessionId();
        return taskSession == null || !active.equals(taskSession);
    }

    private void drainDeferredTasks() {
        DownloadTask first = deferredTasks.peek();
        if (first == null) return;

        String nextSession = first.getContext() == null ? null : first.getContext().getBulkSessionId();
        if (nextSession != null && !nextSession.isBlank()) {
            exclusiveSessionId = nextSession;
            DownloadLog.info("DownloadManager", "Exclusive session resumed from deferred queue: " + nextSession);
            drainDeferredBulkSession(nextSession);
            return;
        }

        while ((first = deferredTasks.peek()) != null) {
            String sessionId = first.getContext() == null ? null : first.getContext().getBulkSessionId();
            if (sessionId != null && !sessionId.isBlank()) break;
            DownloadTask task = deferredTasks.poll();
            submitDeferredTask(task);
        }

        first = deferredTasks.peek();
        String nextBulkSession = first == null || first.getContext() == null
                ? null
                : first.getContext().getBulkSessionId();
        if (nextBulkSession != null && !nextBulkSession.isBlank()) {
            exclusiveSessionId = nextBulkSession;
            DownloadLog.info("DownloadManager", "Exclusive session resumed after deferred standalone tasks: "
                    + nextBulkSession);
            drainDeferredBulkSession(nextBulkSession);
        }
    }

    private void drainDeferredBulkSession(String sessionId) {
        int guard = deferredTasks.size();
        for (int i = 0; i < guard; i++) {
            DownloadTask task = deferredTasks.poll();
            if (task == null) break;
            String taskSession = task.getContext() == null ? null : task.getContext().getBulkSessionId();
            if (sessionId.equals(taskSession)) {
                submitDeferredTask(task);
            } else {
                deferredTasks.add(task);
            }
        }
    }

    private void submitDeferredTask(DownloadTask task) {
        if (task == null) return;
        DownloadLog.info("DownloadManager", "Submitting deferred task: "
                + DownloadLog.taskLabel(task.getContext()));
        task.setDeferredByExclusiveSession(false);
        executor.submit(task);
    }

    public void showSidebar(Parent root) {
        if (root == null) return;

        Runnable show = () -> {
            try {
                QueueController queueController = QueueController.getInstance();
                if (queueController != null && QueueController.isQueueVisible()) {
                    queueController.closeFromOwner();
                }
                DownloadSidebarMenuController controller = getSidebarController();
                if (controller == null) {
                    FXMLLoader loader = new FXMLLoader(DownloadManager.class.getResource(
                            "/io/github/guillermodubon/musicplayer/Views/components/layoutComponents/downloadSideBarMenu/DownloadSideBarMenu.fxml"
                    ));
                    loader.load();
                    controller = loader.getController();
                    setSidebarController(controller);
                }
                if (controller != null) {
                    controller.showInRoot(root);
                }
            } catch (IOException e) {
                DownloadLog.error("DownloadManager", "Could not show download sidebar", e);
            }
        };

        if (Platform.isFxApplicationThread()) {
            show.run();
        } else {
            Platform.runLater(show);
        }
    }

    public void submitTaskWithoutAdding(DownloadTask task) {
        if (task == null) return;
        DownloadLog.info("DownloadManager", "Submitting task without adding to list: "
                + DownloadLog.taskLabel(task.getContext()));
        task.setDeferredByExclusiveSession(false);
        executor.submit(task);
    }

    public void retryTask(DownloadTask sourceTask) {
        if (sourceTask == null) return;

        DownloadTask retry = DownloadTask.copyOf(sourceTask);
        DownloadLog.info("DownloadManager", "Retry requested: " + DownloadLog.taskLabel(retry.getContext()));

        int idx = tasks.indexOf(sourceTask);
        if (idx >= 0) {
            if (Platform.isFxApplicationThread()) {
                tasks.set(idx, retry);
            } else {
                Platform.runLater(() -> tasks.set(idx, retry));
            }
        } else {
            if (Platform.isFxApplicationThread()) {
                tasks.add(retry);
            } else {
                Platform.runLater(() -> tasks.add(retry));
            }
        }

        submitTaskWithoutAdding(retry);
    }

    public boolean hasExistingTask(String query, File targetDir, String cleanSongName) {
        if (query == null && cleanSongName == null) return false;

        for (DownloadTask t : tasks) {
            if (query != null && query.equalsIgnoreCase(t.getQuery())) return true;

            if (cleanSongName != null
                    && t.getCleanSongName() != null
                    && cleanSongName.equalsIgnoreCase(t.getCleanSongName())
                    && targetDir != null
                    && t.getTargetDir() != null
                    && targetDir.getAbsolutePath().equals(t.getTargetDir().getAbsolutePath())) {
                return true;
            }
        }
        return false;
    }

    private static class DaemonThreadFactory implements ThreadFactory {
        private final ThreadFactory defaultFactory = Executors.defaultThreadFactory();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = defaultFactory.newThread(r);
            t.setDaemon(true);
            t.setName("download-worker-" + t.getId());
            return t;
        }
    }
}
