package io.github.guillermodubon.musicplayer.repository.userData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Resolves all user-owned files outside the installation directory.
 *
 * <p>The application installation is read-only from the user's point of
 * view. Database, manifest, extracted media tools and logs are stored under
 * {@code %LOCALAPPDATA%\\MusicPlayer}. A home-directory fallback keeps the
 * application usable when Windows does not expose LOCALAPPDATA.</p>
 */
public final class UserDataPaths {

    private static final String APPLICATION_DIRECTORY_NAME = "MusicPlayer";
    private static final String LOCAL_APP_DATA_ENVIRONMENT_VARIABLE = "LOCALAPPDATA";

    private static final Path LEGACY_SOURCE_DATA_DIRECTORY = Path.of(
            "src", "main", "java", "io", "github", "guillermodubon",
            "musicplayer", "repository", "userData"
    );
    private static final Path LEGACY_ORG_SOURCE_DATA_DIRECTORY = Path.of(
            "src", "main", "java", "org", "example", "musicplayer",
            "repository", "userData"
    );
    private static final Path LEGACY_DATABASE_FILE = Path.of(
            "src", "main", "java", "io", "github", "guillermodubon",
            "musicplayer", "MusicTest.db"
    );
    private static final Path LEGACY_ORG_DATABASE_FILE = Path.of(
            "src", "main", "java", "org", "example", "musicplayer", "MusicTest.db"
    );
    private static final Path LEGACY_ROOT_MANIFEST_FILE = Path.of("manifest.json");

    private UserDataPaths() {
    }

    public static synchronized Path applicationDirectory() {
        return resolveApplicationDirectory();
    }

    public static synchronized Path dataDirectory() {
        return ensureDirectory(resolveApplicationDirectory().resolve("data"));
    }

    public static synchronized Path runtimeDependenciesDirectory() {
        return ensureDirectory(resolveApplicationDirectory().resolve("runtime-dependencies"));
    }

    public static synchronized Path logsDirectory() {
        return ensureDirectory(resolveApplicationDirectory().resolve("logs"));
    }

    public static synchronized Path databaseFile() {
        prepareStorage();
        Path target = dataDirectory().resolve("UserDataBase.db");
        migrateFirstAvailable(target, List.of(
                LEGACY_SOURCE_DATA_DIRECTORY.resolve("UserDataBase.db"),
                LEGACY_SOURCE_DATA_DIRECTORY.resolve("MusicTest.db"),
                LEGACY_ORG_SOURCE_DATA_DIRECTORY.resolve("UserDataBase.db"),
                LEGACY_ORG_SOURCE_DATA_DIRECTORY.resolve("MusicTest.db"),
                LEGACY_DATABASE_FILE,
                LEGACY_ORG_DATABASE_FILE
        ));
        return target;
    }

    public static synchronized Path manifestFile() {
        prepareStorage();
        Path target = dataDirectory().resolve("manifest.json");
        migrateFirstAvailable(target, List.of(
                LEGACY_SOURCE_DATA_DIRECTORY.resolve("manifest.json"),
                LEGACY_ORG_SOURCE_DATA_DIRECTORY.resolve("manifest.json"),
                LEGACY_ROOT_MANIFEST_FILE
        ));
        return target;
    }

    private static void prepareStorage() {
        try {
            Files.createDirectories(applicationDirectory());
            Files.createDirectories(dataDirectory());
            Files.createDirectories(runtimeDependenciesDirectory());
            Files.createDirectories(logsDirectory());
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not prepare user data storage under " + applicationDirectory(),
                    exception
            );
        }
    }

    private static Path resolveApplicationDirectory() {
        String localAppData = System.getenv(LOCAL_APP_DATA_ENVIRONMENT_VARIABLE);
        String baseDirectory = hasText(localAppData)
                ? localAppData
                : System.getProperty("user.home");

        if (!hasText(baseDirectory)) {
            throw new IllegalStateException(
                    "Neither LOCALAPPDATA nor user.home is available for user data storage."
            );
        }

        return Path.of(baseDirectory).resolve(APPLICATION_DIRECTORY_NAME)
                .toAbsolutePath()
                .normalize();
    }

    private static Path ensureDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
            return directory.toAbsolutePath().normalize();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create directory " + directory, exception);
        }
    }

    private static void migrateFirstAvailable(Path target, List<Path> candidates) {
        if (Files.exists(target)) return;

        for (Path candidate : candidates) {
            if (!Files.isRegularFile(candidate)) continue;
            try {
                Files.copy(
                        candidate,
                        target,
                        StandardCopyOption.COPY_ATTRIBUTES
                );
                return;
            } catch (IOException exception) {
                throw new IllegalStateException(
                        "Could not migrate legacy user data from " + candidate + " to " + target,
                        exception
                );
            }
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
