package io.github.guillermodubon.musicplayer.controllers.ui.screens.discoverPage.providers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.MusicCardData;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.factory.CardFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.discoverPage.context.DiscoverPageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.discoverPage.providers.base.BaseDiscoverPagePageSectionProvider;
import io.github.guillermodubon.musicplayer.utils.MusicCardHelper;
import io.github.guillermodubon.musicplayer.services.api.DeezerApiService;
import io.github.guillermodubon.musicplayer.utils.DiscoverUtils;
import io.github.guillermodubon.musicplayer.utils.AlbumArtistResolver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;

/** Renders Deezer editorial releases, alternating singles and albums when both are available. */
public class LatestReleasesSectionProvider extends BaseDiscoverPagePageSectionProvider {

    private static final int EDITORIALS_TO_QUERY = 3;
    private static final int RELEASES_PER_EDITORIAL = 10;

    public LatestReleasesSectionProvider(DiscoverPageContext context) {
        super(context);
    }

    @Override
    public void render(VBox container) {
        VBox section = sectionBlock("Latest releases");
        container.getChildren().add(section);
        int generation = captureRenderGeneration(container);

        supplyAsync(this::loadReleaseCandidates)
                .whenComplete((candidates, error) -> Platform.runLater(() -> {
                    if (!isRenderCurrent(container, generation)) return;
                    if (candidates == null || candidates.isEmpty()) {
                        section.getChildren().setAll(sectionTitle("Latest releases"), emptyState("No recent releases available"));
                        return;
                    }

                    FlowPane content = createContentFlow(container);
                    section.getChildren().setAll(sectionTitle("Latest releases"), content);
                    appendNodesInBatches(container, generation, content, candidates, this::createReleaseCard, rendered -> {
                        if (rendered == 0) {
                            section.getChildren().setAll(sectionTitle("Latest releases"), emptyState("No recent releases available"));
                        }
                    });
                }));
    }

    private List<ReleaseCandidate> loadReleaseCandidates() {
        if (context.endpoints() == null || shouldAbort()) return List.of();

        LinkedHashMap<Long, ReleaseCandidate> albums = new LinkedHashMap<>();
        LinkedHashMap<Long, ReleaseCandidate> singles = new LinkedHashMap<>();

        for (Long editorialId : loadEditorialIds()) {
            if (shouldAbort() || totalCandidates(singles, albums) >= GLOBAL_MAX) break;
            JsonObject releases = getJson(context.endpoints().regionalEditorialReleases(editorialId, RELEASES_PER_EDITORIAL));
            addCandidates(releases, singles, albums);
        }

        // A regional editorial can legitimately be empty. Chart albums keep the section
        // useful without tying it to the listener's local library or profile data.
        if (totalCandidates(singles, albums) < GLOBAL_MAX && !shouldAbort()) {
            addCandidates(getJson(context.endpoints().genreAlbums(0)), singles, albums);
        }

        return interleaveSinglesAndAlbums(singles, albums);
    }

    private List<Long> loadEditorialIds() {
        JsonObject root = getJson(context.endpoints().editorialCatalog());
        if (root == null || !root.has("data") || !root.get("data").isJsonArray()) return List.of();

        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (JsonElement element : root.getAsJsonArray("data")) {
            if (shouldAbort() || !element.isJsonObject()) break;
            long id = DeezerApiService.safeGetLong(element.getAsJsonObject(), "id", -1L);
            if (id > 0) ids.add(id);
            if (ids.size() >= EDITORIALS_TO_QUERY) break;
        }
        return List.copyOf(ids);
    }

    private void addCandidates(JsonObject root,
                               LinkedHashMap<Long, ReleaseCandidate> singles,
                               LinkedHashMap<Long, ReleaseCandidate> albums) {
        if (root == null || shouldAbort()) return;
        List<JsonElement> entries = releaseEntries(root);
        for (JsonElement element : entries) {
            if (shouldAbort() || totalCandidates(singles, albums) >= GLOBAL_MAX) return;
            ReleaseCandidate candidate = candidateFromJson(element);
            if (candidate == null) continue;
            (candidate.single() ? singles : albums).putIfAbsent(candidate.id(), candidate);
        }
    }

    private List<JsonElement> releaseEntries(JsonObject root) {
        if (root.has("data") && root.get("data").isJsonArray()) {
            return root.getAsJsonArray("data").asList();
        }
        if (root.has("releases") && root.get("releases").isJsonObject()) {
            JsonObject releases = root.getAsJsonObject("releases");
            if (releases.has("data") && releases.get("data").isJsonArray()) {
                return releases.getAsJsonArray("data").asList();
            }
        }
        return List.of();
    }

    private int totalCandidates(LinkedHashMap<Long, ReleaseCandidate> singles,
                                LinkedHashMap<Long, ReleaseCandidate> albums) {
        return singles.size() + albums.size();
    }

    private ReleaseCandidate candidateFromJson(JsonElement element) {
        if (element == null || !element.isJsonObject()) return null;
        JsonObject release = element.getAsJsonObject();
        long id = DeezerApiService.safeGetLong(release, "id", -1L);
        if (id <= 0) return null;

        String title = DeezerApiService.extractTitle(release);
        List<String> names = DiscoverUtils.normalizeArtistNames(extractAlbumArtistNamesFromResource(release));
        List<Long> artistIds = List.copyOf(extractAlbumArtistIdsFromResource(release));
        String coverUrl = DeezerApiService.extractHighResolutionCoverUrl(release);

        // Editorial responses are compact. One album lookup fills missing visual data,
        // but avoids a request for entries that already contain all display information.
        if ((coverUrl == null || coverUrl.isBlank()
                || (names.size() <= 1 && !AlbumArtistResolver.hasExplicitOwnerCollection(release)))
                && !shouldAbort()) {
            JsonObject detail = getJson(context.endpoints().albumById(id));
            if (detail != null) {
                release = detail;
                title = DeezerApiService.extractTitle(detail);
                names = DiscoverUtils.normalizeArtistNames(extractAlbumArtistNamesFromResource(detail));
                artistIds = List.copyOf(extractAlbumArtistIdsFromResource(detail));
                coverUrl = DeezerApiService.extractHighResolutionCoverUrl(detail);
            }
        }

        if (names == null || names.isEmpty()) names = List.of("Unknown");
        return new ReleaseCandidate(id, title, names, artistIds, coverUrl, isSingle(release));
    }

    private boolean isSingle(JsonObject release) {
        if (release == null) return false;
        try {
            if (release.has("record_type") && !release.get("record_type").isJsonNull()) {
                return "single".equalsIgnoreCase(release.get("record_type").getAsString());
            }
            return release.has("nb_tracks") && release.get("nb_tracks").getAsInt() == 1;
        } catch (Exception ignored) {
            return false;
        }
    }

    private List<ReleaseCandidate> interleaveSinglesAndAlbums(LinkedHashMap<Long, ReleaseCandidate> singles,
                                                                LinkedHashMap<Long, ReleaseCandidate> albums) {
        List<ReleaseCandidate> singleValues = new ArrayList<>(singles.values());
        List<ReleaseCandidate> albumValues = new ArrayList<>(albums.values());
        List<ReleaseCandidate> result = new ArrayList<>(GLOBAL_MAX);
        int singleIndex = 0;
        int albumIndex = 0;
        boolean preferSingle = !singleValues.isEmpty();

        while (result.size() < GLOBAL_MAX && (singleIndex < singleValues.size() || albumIndex < albumValues.size())) {
            if (preferSingle && singleIndex < singleValues.size()) {
                result.add(singleValues.get(singleIndex++));
            } else if (!preferSingle && albumIndex < albumValues.size()) {
                result.add(albumValues.get(albumIndex++));
            } else if (singleIndex < singleValues.size()) {
                result.add(singleValues.get(singleIndex++));
            } else {
                result.add(albumValues.get(albumIndex++));
            }
            preferSingle = !preferSingle;
        }
        return result;
    }

    private StackPane createReleaseCard(ReleaseCandidate candidate) {
        if (candidate == null) return null;
        try {
            Image cover = candidate.coverUrl() == null || candidate.coverUrl().isBlank()
                    ? defaultCover()
                    : DiscoverUtils.remoteImage(candidate.coverUrl());
            StackPane card = (StackPane) CardFactory.createMusicCard(new MusicCardData(
                    String.valueOf(candidate.id()),
                    MusicCardHelper.coverOrDefault(cover, defaultCover()),
                    candidate.title() == null || candidate.title().isBlank() ? "Release" : candidate.title(),
                    candidate.artistNames(),
                    context.musicActions().albumClick(null),
                    context.musicActions().artistNameClick(null)
            ));
            card.getProperties().put("albumId", candidate.id());
            card.getProperties().put("releaseType", candidate.single() ? "single" : "album");
            card.getProperties().put("artistIds", new ArrayList<>(candidate.artistIds()));
            card.getProperties().put("artistNames", candidate.artistNames());
            if (candidate.coverUrl() != null && !candidate.coverUrl().isBlank()) {
                card.getProperties().put("coverUrl", candidate.coverUrl());
            }
            return card;
        } catch (IOException ignored) {
            return null;
        }
    }

    private record ReleaseCandidate(long id,
                                    String title,
                                    List<String> artistNames,
                                    List<Long> artistIds,
                                    String coverUrl,
                                    boolean single) {
        private ReleaseCandidate {
            artistNames = artistNames == null ? List.of("Unknown") : List.copyOf(artistNames);
            artistIds = artistIds == null ? List.of() : List.copyOf(artistIds);
        }
    }
}
