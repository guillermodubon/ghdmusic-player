#define MyAppName "GHDMusic"
#define MyAppVersion "1.0.0"
#define MyAppPublisher "Guillermo Dubon"
#define MyAppExeName "GHDMusic.exe"

[Setup]
AppId={{B8A6D4C7-4D8A-4C03-9E6F-1D1B6F8A5F31}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL=https://github.com/guillermodubon
DefaultDirName={localappdata}\Programs\GHDMusic
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=..\target\jpackage\installer
OutputBaseFilename=GHDMusic-Setup
SetupIconFile=..\src\main\resources\io\github\guillermodubon\musicplayer\assets\icons\app_logo.ico
UninstallDisplayIcon={app}\{#MyAppExeName}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional shortcuts:"

[Files]
Source: "..\target\jpackage\app-image\GHDMusic\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "licenses\THIRD-PARTY-NOTICES.txt"; DestDir: "{app}\licenses"; Flags: ignoreversion
Source: "licenses\YT-DLP-LICENSE.txt"; DestDir: "{app}\licenses"; Flags: ignoreversion
Source: "..\src\main\resources\io\github\guillermodubon\musicplayer\dependencies\ffmpeg-2026-04-22-git-162ad61486-essentials_build\LICENSE"; DestDir: "{app}\licenses"; DestName: "FFmpeg-GPLv3.txt"; Flags: ignoreversion
Source: "..\src\main\resources\io\github\guillermodubon\musicplayer\dependencies\ffmpeg-2026-04-22-git-162ad61486-essentials_build\README.txt"; DestDir: "{app}\licenses"; DestName: "FFmpeg-BUILD-INFO.txt"; Flags: ignoreversion

[Icons]
Name: "{userprograms}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{userdesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "Launch {#MyAppName}"; Flags: nowait postinstall skipifsilent
