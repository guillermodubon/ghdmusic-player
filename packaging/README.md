# GHDMusic Windows packaging

The project is packaged in two stages:

1. Maven builds the modular application and copies its runtime dependencies.
2. `jpackage --type app-image` creates a self-contained application image with
   its own Java runtime.
3. Inno Setup 6 can wrap that image into `GHDMusic-Setup.exe`.

## Prerequisites

- Windows x64.
- JDK 21 with `java`, `jar` and `jpackage` available through `JAVA_HOME` or
  `PATH`.
- Inno Setup 6 (`ISCC.exe`) for the installer step.

## Build the application image

From the repository root:

```powershell
.\packaging\build-app-image.ps1
```

The image is created at:

```text
target\jpackage\app-image\GHDMusic\
```

It includes the application, the Java runtime and the media tools prepared
locally for the source build. See the repository README for the required
`yt-dlp.exe` and FFmpeg files.

## Build the installer

After installing Inno Setup 6 and making `ISCC.exe` available:

```powershell
.\packaging\build-installer.ps1
```

The installer is written to `target\jpackage\installer\GHDMusic-Setup.exe`.
The installer is per-user and does not require administrator privileges.

## User data

The installed application never writes its database or manifest into the
installation directory. It uses:

```text
%LOCALAPPDATA%\MusicPlayer\data\UserDataBase.db
%LOCALAPPDATA%\MusicPlayer\data\manifest.json
%LOCALAPPDATA%\MusicPlayer\runtime-dependencies\
%LOCALAPPDATA%\MusicPlayer\logs\
```

Existing development files are copied once when no external file exists. The
repository's database and manifest are excluded by `.gitignore` and are not
part of the packaged application.

## Repository hygiene

The repository tracks source code, JavaFX views, stylesheets, application
assets, packaging scripts, and third-party license notices.

The following files are intentionally not tracked:

- Maven, IntelliJ IDEA, and jpackage build output under `target\`, `build\`,
  `dist\`, and `out\`.
- Local IDE metadata and workspace-specific settings.
- Locally installed yt-dlp and FFmpeg files required for source builds.
- User databases, manifests, downloaded music, logs, and temporary files.
- Environment files, credentials, private keys, and local secrets.

## Third-party components

The installer includes license information for yt-dlp and FFmpeg under the
installed application's `licenses` directory. Keep the applicable notices and
FFmpeg build metadata when packaging the application.
