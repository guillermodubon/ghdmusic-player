package io.github.guillermodubon.musicplayer.models;

import java.util.ArrayList;
import java.util.List;

public class DeezerApiMetaData {

    private String songFileName;
    private List<String> albumArtistNames;
    private List<List<byte[]>> albumArtistsPortraitBytes;
    private String albumName;
    private List<byte[]> albumCoverBytesList;
    private String albumReleaseDate;
    private String recordType;
    private String genre;
    private String songName;
    private List<String> songContributorNames;
    private List<List<byte[]>> songContributorsPortraitBytes;
    private int trackOrder;
    private int numberOfTracks;
    private List<Long> albumArtistIds;
    private List<Long> songContributorIds;
    private long albumId;
    private long trackId;
    private String albumCoverUrl;
    private int albumGenreId;

    public DeezerApiMetaData(
            long albumId,
            String songFileName,
            List<String> albumArtistNames,
            List<List<byte[]>> albumArtistsPortraitBytes,
            String albumName,
            List<byte[]> albumCoverBytesList,
            String albumReleaseDate,
            String recordType,
            String genre,
            String songName,
            List<String> songContributorNames,
            List<List<byte[]>> songContributorsPortraitBytes,
            int trackOrder,
            int numberOfTracks,
            List<Long> albumArtistIds,
            List<Long> songContributorIds,
            long trackId,
            String albumCoverUrl,
            int albumGenreId
    ) {
        this.albumId = albumId;
        this.songFileName = songFileName;
        this.albumArtistNames = albumArtistNames;
        this.albumArtistsPortraitBytes = albumArtistsPortraitBytes;
        this.albumName = albumName;
        this.albumCoverBytesList = albumCoverBytesList;
        this.albumReleaseDate = albumReleaseDate;
        this.recordType = recordType;
        this.genre = genre;
        this.songName = songName;
        this.songContributorNames = songContributorNames;
        this.songContributorsPortraitBytes = songContributorsPortraitBytes;
        this.trackOrder = trackOrder;
        this.numberOfTracks = numberOfTracks;
        this.albumArtistIds = albumArtistIds;
        this.songContributorIds = songContributorIds;
        this.trackId = trackId;
        this.albumCoverUrl = albumCoverUrl;
        this.albumGenreId=albumGenreId;
    }


    public DeezerApiMetaData() {

        this.albumArtistNames = new ArrayList<>();
        this.albumArtistsPortraitBytes = new ArrayList<>();
        this.albumCoverBytesList = new ArrayList<>();
        this.songContributorNames = new ArrayList<>();
        this.songContributorsPortraitBytes = new ArrayList<>();
        this.albumArtistIds = new ArrayList<>();
        this.songContributorIds = new ArrayList<>();
        this.albumId = 0L;
        this.trackId = 0L;
        this.trackOrder = 0;
        this.numberOfTracks = 0;
        this.albumGenreId = 0;
    }

    public String getSongFileName() {
        return songFileName;
    }

    public void setSongFileName(String songFileName) {
        this.songFileName = songFileName;
    }

    public List<String> getAlbumArtistNames() {
        return albumArtistNames;
    }

    public void setAlbumArtistNames(List<String> albumArtistNames) {
        this.albumArtistNames = albumArtistNames;
    }

    public List<List<byte[]>> getAlbumArtistsPortraitBytes() {
        return albumArtistsPortraitBytes;
    }

    public void setAlbumArtistsPortraitBytes(List<List<byte[]>> albumArtistsPortraitBytes) {
        this.albumArtistsPortraitBytes = albumArtistsPortraitBytes;
    }

    public String getAlbumName() {
        return albumName;
    }

    public void setAlbumName(String albumName) {
        this.albumName = albumName;
    }

    public List<byte[]> getAlbumCoverBytesList() {
        return albumCoverBytesList;
    }

    public void setAlbumCoverBytesList(List<byte[]> albumCoverBytesList) {
        this.albumCoverBytesList = albumCoverBytesList;
    }

    public String getAlbumReleaseDate() {
        return albumReleaseDate;
    }

    public void setAlbumReleaseDate(String albumReleaseDate) {
        this.albumReleaseDate = albumReleaseDate;
    }

    public String getRecordType() {
        return recordType;
    }

    public void setRecordType(String recordType) {
        this.recordType = recordType;
    }

    public String getGenre() {
        return genre;
    }

    public void setGenre(String genre) {
        this.genre = genre;
    }

    public String getSongName() {
        return songName;
    }

    public void setSongName(String songName) {
        this.songName = songName;
    }

    public List<String> getSongContributorNames() {
        return songContributorNames;
    }

    public void setSongContributorNames(List<String> songContributorNames) {
        this.songContributorNames = songContributorNames;
    }

    public List<List<byte[]>> getSongContributorsPortraitBytes() {
        return songContributorsPortraitBytes;
    }

    public void setSongContributorsPortraitBytes(List<List<byte[]>> songContributorsPortraitBytes) {
        this.songContributorsPortraitBytes = songContributorsPortraitBytes;
    }

    public int getTrackOrder() {
        return trackOrder;
    }
    public void setTrackOrder(int trackOrder) {
        this.trackOrder = trackOrder;
    }

    public int getNumberOfTracks() {
        return numberOfTracks;
    }

    public void setNumberOfTracks(int numberOfTracks) {
        this.numberOfTracks = numberOfTracks;
    }

    public List<Long> getAlbumArtistIds() {
        return albumArtistIds;
    }

    public void setAlbumArtistIds(List<Long> albumArtistIds) {
        this.albumArtistIds = albumArtistIds;
    }

    public List<Long> getSongContributorIds() {
        return songContributorIds;
    }

    public void setSongContributorIds(List<Long> songContributorIds) {
        this.songContributorIds = songContributorIds;
    }

    public long getAlbumId() {
        return albumId;
    }

    public void setAlbumId(long albumId) {
        this.albumId = albumId;
    }

    public long getTrackId() {
        return trackId;
    }

    public void setTrackId(long trackId) {
        this.trackId = trackId;
    }

    public String getAlbumCoverUrl() {
        return albumCoverUrl;
    }

    public int getAlbumGenreId() {
        return albumGenreId;
    }

}