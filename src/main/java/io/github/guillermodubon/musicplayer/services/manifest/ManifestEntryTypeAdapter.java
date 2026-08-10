package io.github.guillermodubon.musicplayer.services.manifest;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import io.github.guillermodubon.musicplayer.models.ManifestEntry;

import java.io.IOException;

/** Gson adapter for the compact manifest entry representation. */
final class ManifestEntryTypeAdapter extends TypeAdapter<ManifestEntry> {
    @Override
    public void write(JsonWriter out, ManifestEntry value) throws IOException {
        out.beginObject();
        out.name("deezerId").value(value.deezerId);
        out.name("lastModified").value(value.lastModified);
        out.name("fileSize").value(value.fileSize);
        if (value.fileName != null && !value.fileName.isBlank()) {
            out.name("fileName").value(value.fileName);
        }
        out.endObject();
    }

    @Override
    public ManifestEntry read(JsonReader in) throws IOException {
        in.beginObject();
        long deezerId = 0;
        long lastModified = 0;
        long fileSize = 0;
        String fileName = null;
        while (in.hasNext()) {
            String name = in.nextName();
            if ("deezerId".equals(name)) {
                deezerId = in.nextLong();
            } else if ("lastModified".equals(name)) {
                lastModified = in.nextLong();
            } else if ("fileSize".equals(name)) {
                fileSize = in.nextLong();
            } else if ("fileName".equals(name)) {
                if (in.peek() == JsonToken.NULL) {
                    in.nextNull();
                } else {
                    fileName = in.nextString();
                }
            } else {
                in.skipValue();
            }
        }
        in.endObject();
        return new ManifestEntry(deezerId, lastModified, fileSize, fileName);
    }
}
