package io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.providers;

import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;
import io.github.guillermodubon.musicplayer.repository.dao.history.PlaybackHistoryDao;
import io.github.guillermodubon.musicplayer.repository.dao.history.PlaybackHistoryDaoImpl;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.RecentlyPlayedMusicCard;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.RecentlyPlayedCardData;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.factory.CardFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.context.HomePageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.providers.base.BaseHomePageSectionProvider;
import io.github.guillermodubon.musicplayer.models.PlaybackHistory;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.api.DeezerApiService;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;


public class RecentlyPlayedSectionProvider extends BaseHomePageSectionProvider {

    /**
     * Decode the artwork above the display size so the 56px card remains
     * sharp on high-DPI displays without loading unnecessarily large images.
     * The resolver still reuses its typed/cache entries and the section only
     * keeps up to eight covers alive at once.
     */
    private static final double COVER_SIZE = 320;
    private static final String COVER_PREFERRED_TYPE = "xl";
    private static final double RECENT_COVER_DISPLAY_SIZE = 56;
    private static final double RECENT_COVER_CORNER_RADIUS = 10;
    private static final Map<String, CompletableFuture<Image>> REMOTE_COVER_IN_FLIGHT = new ConcurrentHashMap<>();

    public RecentlyPlayedSectionProvider(HomePageContext context) {
        super(context);
    }

    @Override
    public CompletableFuture<Void> render(VBox container, String filter, long renderId) {
        if (!isRenderActive(renderId)) return CompletableFuture.completedFuture(null);

        VBox section = sectionBlock(container, "Recently Played");
        GridPane grid = new GridPane();
        grid.setMaxWidth(Double.MAX_VALUE);
        grid.setHgap(12);
        grid.setVgap(12);
        grid.getStyleClass().add("mainmenu-recent-grid");
        VBox.setVgrow(section, Priority.NEVER);
        VBox.setVgrow(grid, Priority.NEVER);
        section.getChildren().add(grid);

        CompletableFuture<Void> completion = new CompletableFuture<>();
        supplyAsync(this::loadRecentEntries)
                .whenComplete((entries, th) -> Platform.runLater(() -> {
                    try {
                        if (!isRenderActive(renderId)) return;

                        grid.getChildren().clear();
                        if (entries == null || entries.isEmpty()) {
                            removeSection(section);
                            return;
                        }

                        List<Parent> cards = new ArrayList<>();
                        for (RecentEntry recent : entries) {
                            if (!isRenderActive(renderId)) return;

                            PlaybackHistory entry = recent.history();
                            long listId = entry.getItemId();
                            String titleText = entry.getName() == null ? "Unknown" : entry.getName();
                            Image cover = recent.cover();

                            try {
                                RecentlyPlayedCardData data = new RecentlyPlayedCardData(
                                        String.valueOf(listId), cover, titleText, actionForEntry(entry));
                                Parent card = CardFactory.createRecentlyPlayedCard(data);
                                styleRecentlyPlayedCard(card);
                                enforceSquareCover(card);
                                cards.add(card);

                                if (recent.fetchRemoteCover()) {
                                    upgradeCoverFromDeezer(entry, card, renderId);
                                }
                            } catch (Exception ex) {
                                ex.printStackTrace();
                            }

                            if (cards.size() >= 8) break;
                        }

                        if (cards.isEmpty()) {
                            removeSection(section);
                            return;
                        }

                        Runnable relayout = () -> layoutRecentGrid(grid, cards, recentGridColumns(resolveLayoutWidth(section, grid)));
                        section.widthProperty().addListener((obs, oldWidth, newWidth) -> relayout.run());
                        grid.widthProperty().addListener((obs, oldWidth, newWidth) -> relayout.run());
                        relayout.run();
                        Platform.runLater(relayout);
                    } finally {
                        completion.complete(null);
                    }
                }));
        return completion;
    }

    private double resolveLayoutWidth(VBox section, GridPane grid) {
        if (section != null && section.getWidth() > 1) return section.getWidth();
        if (grid != null && grid.getWidth() > 1) return grid.getWidth();
        if (section != null && section.getParent() instanceof Region parent && parent.getWidth() > 1) {
            return parent.getWidth();
        }
        return 0;
    }

    private void layoutRecentGrid(GridPane grid, List<Parent> cards, int columns) {
        if (grid == null || cards == null) return;

        int safeColumns = Math.max(1, Math.min(4, columns));
        int visibleCount = Math.min(8, cards.size());
        grid.getChildren().clear();
        grid.getColumnConstraints().clear();

        if (visibleCount <= 0) {
            setRecentGridHeight(grid, 0);
            return;
        }

        for (int c = 0; c < safeColumns; c++) {
            ColumnConstraints constraints = new ColumnConstraints();
            constraints.setPercentWidth(100.0 / safeColumns);
            constraints.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(constraints);
        }

        for (int i = 0; i < visibleCount; i++) {
            Parent card = cards.get(i);
            styleRecentlyPlayedCard(card);
            if (card instanceof Region region) {
                region.setMaxWidth(Double.MAX_VALUE);
            }

            int col = i % safeColumns;
            int row = i / safeColumns;
            grid.add(card, col, row);
            GridPane.setHgrow(card, Priority.ALWAYS);
            GridPane.setFillWidth(card, true);
        }

        int rows = (int) Math.ceil(visibleCount / (double) safeColumns);
        double cardHeight = 70.0;
        double height = rows * cardHeight + Math.max(0, rows - 1) * grid.getVgap();
        setRecentGridHeight(grid, height);
    }

    private void setRecentGridHeight(GridPane grid, double height) {
        if (grid == null) return;
        double safeHeight = Math.max(0, height);
        grid.setMinHeight(safeHeight);
        grid.setPrefHeight(safeHeight);
        grid.setMaxHeight(safeHeight);
        grid.requestLayout();
        Parent parent = grid.getParent();
        if (parent != null) parent.requestLayout();
    }

    private int recentGridColumns(double width) {
        if (width <= 0 || width >= 980) return 4;
        if (width >= 720) return 3;
        if (width >= 460) return 2;
        return 1;
    }

    private List<RecentEntry> loadRecentEntries() {
        try {
            PlaybackHistoryDao dao = new PlaybackHistoryDaoImpl(null);
            List<PlaybackHistory> entries = dao.findRecent(32);
            if (entries == null || entries.isEmpty()) return List.of();

            LinkedHashMap<String, PlaybackHistory> unique = new LinkedHashMap<>();
            for (PlaybackHistory p : entries) {
                if (p == null) continue;
                String key = p.getItemType() + ":" + p.getItemId();
                if (!unique.containsKey(key)) unique.put(key, p);
                if (unique.size() >= 8) break;
            }

            List<RecentEntry> result = new ArrayList<>(unique.size());
            for (PlaybackHistory entry : unique.values()) {
                CoverResolution cover = resolveLocalCover(entry);
                result.add(new RecentEntry(entry, cover.image(), cover.fetchRemoteCover()));
            }
            return result;
        } catch (Throwable t) {
            t.printStackTrace();
            return List.of();
        }
    }

    private Consumer<String> actionForEntry(PlaybackHistory entry) {
        if (entry == null || context.musicActions() == null) return id -> {};
        return switch (entry.getItemType()) {
            case "PLAYLIST" -> context.musicActions().playlistClick(null);
            case "SINGLE" -> context.musicActions().songClick(null);
            default -> context.musicActions().albumClick(null);
        };
    }

    /**
     * Resolves only local/known images in the background. A missing image does
     * not delay the section: it is upgraded from Deezer after its card is on
     * screen.
     */
    private CoverResolution resolveLocalCover(PlaybackHistory entry) {
        if (entry == null || entry.getItemId() <= 0) {
            return CoverResolution.fallback(false, defaultCover());
        }

        String type = entry.getItemType() == null ? "ALBUM" : entry.getItemType().trim().toUpperCase();
        if ("PLAYLIST".equals(type)) {
            /*
             * First source: cover art persisted in Playlist.CoverImage.
             *
             * Query by ID to avoid automatically using coverUrl
             * before checking if a cover is stored in SQLite.
             */
            Image databaseCover = MediaImageResolver.playlistCover(
                    entry.getItemId(),
                    COVER_SIZE,
                    COVER_SIZE
            );

            if (isUsable(databaseCover)) {
                return CoverResolution.available(databaseCover);
            }

            Playlist knownPlaylist = findPlaylist(entry.getItemId());

            /*
             * If the playlist does not appear in the local library, it means it is a
             * remote playlist that the user has neither created nor saved to the database.
             *
             * loadRecentEntries() is executed via supplyAsync(...), so this
             * direct query to Deezer does not block the JavaFX thread.
             */
            if (knownPlaylist == null) {
                Image deezerCover = fetchRemoteCover(entry);

                if (isUsable(deezerCover)) {
                    return CoverResolution.available(deezerCover);
                }

                /*
                 * If the direct query fails temporarily, retain the subsequent
                 * asynchronous mechanism as a fallback.
                 */
                return CoverResolution.fallback(
                        true,
                        defaultCover()
                );
            }

            /*
             * The playlist exists locally but lacks a valid cover in the database.
             *
             * For remote playlists saved locally, Deezer remains the fallback.
             * Deezer is not queried for user-created playlists because their
             * ID is local.
             */
            boolean canUseDeezer =
                    !"User".equalsIgnoreCase(
                            knownPlaylist.getAuthorName()
                    );

            return CoverResolution.fallback(
                    canUseDeezer,
                    defaultCover()
            );
        }

        if ("SINGLE".equals(type)) {
            Song knownSong = findSong(entry.getItemId());
            Image cover = knownSong == null
                    ? null
                    : MediaImageResolver.songAlbumCover(
                            knownSong,
                            COVER_PREFERRED_TYPE,
                            COVER_SIZE,
                            COVER_SIZE
                    );
            if (isUsable(cover)) return CoverResolution.available(cover);
            return CoverResolution.fallback(true, defaultCover());
        }

        Image cover = MediaImageResolver.albumCover(
                entry.getItemId(),
                COVER_PREFERRED_TYPE,
                COVER_SIZE,
                COVER_SIZE
        );
        if (isUsable(cover)) return CoverResolution.available(cover);
        return CoverResolution.fallback(true, defaultCover());
    }

    private Playlist findPlaylist(long playlistId) {
        try {
            for (Playlist playlist : context.memory().playlists()) {
                if (playlist != null && playlist.getId() == playlistId) return playlist;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private Song findSong(long songId) {
        try {
            for (Song song : context.memory().songs()) {
                if (song != null && song.getSongID() == songId) return song;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void upgradeCoverFromDeezer(PlaybackHistory entry, Parent card, long renderId) {
        if (entry == null || card == null || entry.getItemId() <= 0) return;
        String requestKey = (entry.getItemType() == null ? "ALBUM" : entry.getItemType()) + ':' + entry.getItemId();

        CompletableFuture<Image> request = REMOTE_COVER_IN_FLIGHT.get(requestKey);
        if (request == null) {
            CompletableFuture<Image> created = supplyAsync(() -> fetchRemoteCover(entry))
                    .exceptionally(ignored -> null);
            CompletableFuture<Image> existing = REMOTE_COVER_IN_FLIGHT.putIfAbsent(requestKey, created);
            request = existing == null ? created : existing;
            if (existing == null) {
                CompletableFuture<Image> finalCreated = created;
                created.whenComplete((ignored, error) -> REMOTE_COVER_IN_FLIGHT.remove(requestKey, finalCreated));
            }
        }

        request.thenAccept(image -> Platform.runLater(() -> {
            if (!isRenderActive(renderId) || !isUsable(image)) return;
            Object controller = card.getProperties().get("controller");
            if (controller instanceof RecentlyPlayedMusicCard recentCard) {
                recentCard.updateCover(image);
            }
        }));
    }

    private Image fetchRemoteCover(PlaybackHistory entry) {
        if (entry == null || entry.getItemId() <= 0 || context.deezer() == null) return null;

        try {
            String type = entry.getItemType() == null ? "ALBUM" : entry.getItemType().trim().toUpperCase();
            String endpoint = switch (type) {
                case "PLAYLIST" -> "https://api.deezer.com/playlist/" + entry.getItemId();
                case "SINGLE" -> context.endpoints().trackById(entry.getItemId());
                default -> context.endpoints().albumById(entry.getItemId());
            };
            JsonObject payload = getJson(endpoint);
            if (payload == null) return null;

            JsonObject coverSource = payload;
            if ("SINGLE".equals(type) && payload.has("album") && payload.get("album").isJsonObject()) {
                coverSource = payload.getAsJsonObject("album");
            }

            String coverUrl = DeezerApiService.extractCoverUrlFromAlbumOrPlaylist(coverSource);
            return MediaImageResolver.remoteImage(coverUrl, COVER_SIZE, COVER_SIZE);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isUsable(Image image) {
        return image != null
                && !image.isError()
                && image.getWidth() > 1
                && image.getHeight() > 1;
    }

    /**
     * User-created playlists can provide artwork with any aspect ratio. The
     * recently-played design is always a square thumbnail, so normalize the
     * ImageView independently of the source image dimensions.
     */
    private void enforceSquareCover(Parent card) {
        ImageView cover = findCoverImage(card);
        if (cover == null) return;

        cover.setFitWidth(RECENT_COVER_DISPLAY_SIZE);
        cover.setFitHeight(RECENT_COVER_DISPLAY_SIZE);
        cover.setPreserveRatio(false);
        cover.setSmooth(true);

        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(cover.fitWidthProperty());
        clip.heightProperty().bind(cover.fitHeightProperty());
        clip.arcWidthProperty().bind(cover.fitWidthProperty().multiply(
                RECENT_COVER_CORNER_RADIUS / RECENT_COVER_DISPLAY_SIZE
        ));
        clip.arcHeightProperty().bind(cover.fitHeightProperty().multiply(
                RECENT_COVER_CORNER_RADIUS / RECENT_COVER_DISPLAY_SIZE
        ));
        cover.setClip(clip);
    }

    private ImageView findCoverImage(Parent parent) {
        if (parent == null) return null;
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (child instanceof ImageView imageView) return imageView;
            if (child instanceof Parent nested) {
                ImageView result = findCoverImage(nested);
                if (result != null) return result;
            }
        }
        return null;
    }

    private record RecentEntry(PlaybackHistory history, Image cover, boolean fetchRemoteCover) {
    }

    private record CoverResolution(Image image, boolean fetchRemoteCover) {
        private static CoverResolution available(Image image) {
            return new CoverResolution(image, false);
        }

        private static CoverResolution fallback(boolean fetchRemoteCover, Image fallback) {
            return new CoverResolution(fallback, fetchRemoteCover);
        }
    }
}
