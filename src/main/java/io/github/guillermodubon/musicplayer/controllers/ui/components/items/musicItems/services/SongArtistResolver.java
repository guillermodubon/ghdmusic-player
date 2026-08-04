package io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems.services;

import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Song;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class SongArtistResolver {

    private SongArtistResolver() {
    }

    public static List<Artist> resolveParticipants(Song song) {
        if (song == null) return List.of();

        List<Artist> participants = new ArrayList<>();
        addDistinct(participants, song.getArtist());
        if (song.getAlbum() != null) {
            addDistinct(participants, song.getAlbum().getArtist());
        }
        return List.copyOf(participants);
    }

    public static List<Artist> merge(Collection<Artist> current, Collection<Artist> extra) {
        List<Artist> merged = new ArrayList<>();
        addDistinct(merged, current);
        addDistinct(merged, extra);
        return merged;
    }

    private static void addDistinct(List<Artist> target, Collection<Artist> artists) {
        if (artists == null) return;

        for (Artist artist : artists) {
            if (artist == null) continue;

            int duplicateIndex = duplicateIndex(target, artist);
            if (duplicateIndex >= 0) {
                Artist current = target.get(duplicateIndex);
                if (isBetterIdentity(artist, current)) {
                    target.set(duplicateIndex, artist);
                }
                continue;
            }
            target.add(artist);
        }
    }

    private static int duplicateIndex(List<Artist> artists, Artist candidate) {
        for (int i = 0; i < artists.size(); i++) {
            Artist existing = artists.get(i);
            if (existing == candidate) {
                return i;
            }

            long existingId = existing.getArtistID();
            long candidateId = candidate.getArtistID();

            // Deezer IDs are authoritative. Two artists with the same
            // display name but different IDs must remain separate entries.
            if (existingId > 0 && candidateId > 0) {
                if (existingId == candidateId) {
                    return i;
                }
                continue;
            }

            // If one side lacks an ID, a same-name entry may still represent
            // the same artist with incomplete metadata. `isBetterIdentity`
            // then keeps the identified object instead of downgrading it.
            if (sameName(existing, candidate)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isBetterIdentity(Artist candidate, Artist current) {
        if (candidate == null || current == null) return false;
        if (current.getArtistID() <= 0 && candidate.getArtistID() > 0) return true;
        return (current.getName() == null || current.getName().isBlank())
                && candidate.getName() != null
                && !candidate.getName().isBlank();
    }

    private static boolean sameName(Artist left, Artist right) {
        return left.getName() != null
                && right.getName() != null
                && !left.getName().isBlank()
                && left.getName().trim().equalsIgnoreCase(right.getName().trim());
    }
}
