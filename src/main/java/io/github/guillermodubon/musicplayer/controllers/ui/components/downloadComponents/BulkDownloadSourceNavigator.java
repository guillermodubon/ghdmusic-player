package io.github.guillermodubon.musicplayer.controllers.ui.components.downloadComponents;

import javafx.scene.Node;
import io.github.guillermodubon.musicplayer.services.navigation.PlayerMenuNavigator;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.PlayerMenuController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext;
import io.github.guillermodubon.musicplayer.services.downloads.bulk.BulkDownloadSession;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.AlbumPlaybackCoordinator;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.PlaylistPlaybackCoordinator;
import io.github.guillermodubon.musicplayer.services.playback.PlaybackManager;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

public final class BulkDownloadSourceNavigator {

    private BulkDownloadSourceNavigator() {
    }

    public static void open(BulkDownloadSession session, Node probe) {
        if (session == null || probe == null) return;
        long sourceId = session.getSourceId();
        if (sourceId <= 0) return;
        BulkDownloadSession.SourceType sourceType = session.getSourceType();
        if (sourceType == null || sourceType == BulkDownloadSession.SourceType.UNKNOWN) return;
        if (isAlreadyOpen(sourceId, sourceType)) return;

        StartUpService svc = StartUpService.getInstance();
        if (svc == null) return;

        PlayerMenuNavigator navigator = new PlayerMenuNavigator(svc);
        String id = Long.toString(sourceId);
        switch (sourceType) {
            case ALBUM -> new AlbumPlaybackCoordinator(svc, navigator).handle(id, probe);
            case PLAYLIST -> new PlaylistPlaybackCoordinator(svc, navigator).handle(id, probe);
            default -> {
            }
        }
    }

    private static boolean isAlreadyOpen(long sourceId, BulkDownloadSession.SourceType sourceType) {
        try {
            PlayerMenuController current = PlaybackManager.getInstance().getMenuController();
            if (current == null || !current.isCurrentCenterViewVisible()) return false;
            return current.getCurrentPlaylistInViewId() == sourceId
                    && matches(current.getCurrentContentTypeInView(), sourceType);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean matches(PlayerMenuContext.ContentType currentType,
                                   BulkDownloadSession.SourceType sourceType) {
        if (currentType == null || sourceType == null) return false;
        return switch (sourceType) {
            case ALBUM -> currentType == PlayerMenuContext.ContentType.ALBUM;
            case PLAYLIST -> currentType == PlayerMenuContext.ContentType.PLAYLIST;
            case SINGLE -> currentType == PlayerMenuContext.ContentType.SINGLE;
            case UNKNOWN -> false;
        };
    }
}
