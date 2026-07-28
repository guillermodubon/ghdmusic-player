package io.github.guillermodubon.musicplayer.application;

import javafx.scene.image.Image;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Loads the application icons without coupling resource handling to the JavaFX entry point. */
public final class ApplicationIconLoader {

    private static final String APP_ICON_RESOURCE =
            "/io/github/guillermodubon/musicplayer/assets/icons/app_logo.ico";
    private static final String APP_ICON_HIGH_RES_RESOURCE =
            "/io/github/guillermodubon/musicplayer/assets/images/app_logo.png";

    public List<Image> loadIcons() {
        List<Image> icons = new ArrayList<>(2);
        addIfValid(icons, loadIcoIcon());
        addIfValid(icons, loadPngIcon());
        return icons;
    }

    private Image loadIcoIcon() {
        try (InputStream stream = ApplicationIconLoader.class
                .getResourceAsStream(APP_ICON_RESOURCE)) {
            if (stream == null) return null;

            byte[] icoBytes = stream.readAllBytes();
            byte[] imageBytes = extractLargestIcoImage(icoBytes);
            if (imageBytes == null) return null;

            Image image = new Image(new ByteArrayInputStream(imageBytes));
            return image.isError() ? null : image;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Image loadPngIcon() {
        try (InputStream stream = ApplicationIconLoader.class
                .getResourceAsStream(APP_ICON_HIGH_RES_RESOURCE)) {
            if (stream == null) return null;

            Image image = new Image(stream);
            return image.isError() ? null : image;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void addIfValid(List<Image> icons, Image image) {
        if (image != null) icons.add(image);
    }

    private byte[] extractLargestIcoImage(byte[] icoBytes) {
        if (icoBytes == null || icoBytes.length < 6) return null;

        int imageCount = readUnsignedShort(icoBytes, 4);
        int bestOffset = -1;
        int bestSize = 0;
        long bestArea = -1;

        for (int index = 0; index < imageCount; index++) {
            int entryOffset = 6 + index * 16;
            if (entryOffset + 16 > icoBytes.length) break;

            int width = icoBytes[entryOffset] & 0xFF;
            int height = icoBytes[entryOffset + 1] & 0xFF;
            width = width == 0 ? 256 : width;
            height = height == 0 ? 256 : height;

            int size = readInt(icoBytes, entryOffset + 8);
            int offset = readInt(icoBytes, entryOffset + 12);
            if (size <= 0 || offset < 0 || offset > icoBytes.length - size) continue;

            long area = (long) width * height;
            if (area > bestArea) {
                bestArea = area;
                bestOffset = offset;
                bestSize = size;
            }
        }

        return bestOffset < 0
                ? null
                : Arrays.copyOfRange(icoBytes, bestOffset, bestOffset + bestSize);
    }

    private int readUnsignedShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private int readInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }
}
