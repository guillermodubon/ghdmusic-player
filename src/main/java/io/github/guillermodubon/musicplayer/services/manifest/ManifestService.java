package io.github.guillermodubon.musicplayer.services.manifest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import io.github.guillermodubon.musicplayer.models.ManifestEntry;
import io.github.guillermodubon.musicplayer.repository.userData.UserDataPaths;

import java.io.*;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;


public class ManifestService {
    private final File manifestFile;
    private final Gson gson;

    public ManifestService() {
        this.manifestFile = UserDataPaths.manifestFile().toFile();
        GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.registerTypeAdapter(ManifestEntry.class, new ManifestEntryTypeAdapter());
        this.gson = gsonBuilder.create();
    }

    private final Type mapType = new TypeToken<Map<String, ManifestEntry>>() {}.getType();

    /**
     * Loads the previous manifest from manifest.json.
     * @return map of name → entry (with id and lastModified), or null if it does not exist yet.
     */
    public Map<String, ManifestEntry> load() {
        if (!manifestFile.exists()) return null;
        try (Reader reader = new FileReader(manifestFile)) {
            return gson.fromJson(reader, mapType);
        } catch (IOException | JsonSyntaxException e) {
            System.err.println("Error al leer manifest.json: " + e.getMessage());
            return new HashMap<>();
        }
    }
    /**
     * Saves the current manifest to manifest.json.
     */
    public void save(Map<String, ManifestEntry> manifest) {
        try (Writer writer = new FileWriter(manifestFile)) {
            gson.toJson(manifest, writer);
        } catch (IOException e) {
            System.err.println("No pude escribir manifest.json: " + e.getMessage());
        }
    }

    /**
     * Compares two manifests, returning true if they match.
     */
    public boolean equals(Map<String, ManifestEntry> a, Map<String, ManifestEntry> b) {
        if (a == null || b == null) return false;
        return a.equals(b);
    }


}

