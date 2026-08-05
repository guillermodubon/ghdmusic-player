$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$targetRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot 'target\jpackage'))
$workspaceRoot = [IO.Path]::GetFullPath($projectRoot)

function Resolve-JdkHome {
    $candidates = [System.Collections.Generic.List[string]]::new()

    if ($env:JAVA_HOME) {
        $candidates.Add($env:JAVA_HOME)
    }

    $javaCommand = Get-Command 'java.exe' -ErrorAction SilentlyContinue
    if ($javaCommand -and $javaCommand.Source) {
        $javaBin = Split-Path -Parent $javaCommand.Source
        $candidates.Add((Split-Path -Parent $javaBin))
    }

    $candidateRoots = @(
        (Join-Path $env:USERPROFILE '.jdks'),
        (Join-Path $env:ProgramFiles 'Java'),
        (Join-Path $env:ProgramFiles 'Eclipse Adoptium'),
        (Join-Path $env:ProgramFiles 'Microsoft')
    )

    foreach ($root in $candidateRoots) {
        if (Test-Path -LiteralPath $root) {
            Get-ChildItem -Path $root -Directory -ErrorAction SilentlyContinue |
                ForEach-Object { $candidates.Add($_.FullName) }
        }
    }

    foreach ($candidate in $candidates | Select-Object -Unique) {
        if ((Test-Path -LiteralPath (Join-Path $candidate 'bin\java.exe')) -and
            (Test-Path -LiteralPath (Join-Path $candidate 'bin\javac.exe')) -and
            (Test-Path -LiteralPath (Join-Path $candidate 'bin\jpackage.exe')) -and
            (Test-Path -LiteralPath (Join-Path $candidate 'bin\jlink.exe'))) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    return $null
}

$resolvedJdkHome = Resolve-JdkHome
if (-not $resolvedJdkHome) {
    throw 'A JDK 21 with java, javac and jpackage is required. Set JAVA_HOME or install a JDK in a standard Windows location.'
}
$env:JAVA_HOME = $resolvedJdkHome
$env:Path = "$(Join-Path $resolvedJdkHome 'bin');$env:Path"

if (-not $targetRoot.StartsWith($workspaceRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to clean a path outside the project: $targetRoot"
}

if (Test-Path -LiteralPath $targetRoot) {
    Remove-Item -LiteralPath $targetRoot -Recurse -Force
}

$mavenWrapper = Join-Path $projectRoot 'mvnw.cmd'
$inputDirectory = Join-Path $targetRoot 'input'
$imageDirectory = Join-Path $targetRoot 'app-image'
$runtimeDirectory = Join-Path $targetRoot 'runtime'
$appJar = Join-Path $projectRoot 'target\music-player-1.0.0.jar'
$appJarName = 'music-player-1.0.0.jar'
$iconFile = Join-Path $projectRoot 'src\main\resources\io\github\guillermodubon\musicplayer\assets\icons\app_logo.ico'

if (-not (Test-Path -LiteralPath $mavenWrapper)) {
    throw "Maven wrapper not found: $mavenWrapper"
}
if (-not (Test-Path -LiteralPath $iconFile)) {
    throw "Application icon not found: $iconFile"
}

Push-Location $projectRoot
try {
    & $mavenWrapper '-Ppackaging' '-DskipTests' 'clean' 'package'
    if ($LASTEXITCODE -ne 0) {
        throw "Maven packaging build failed with exit code $LASTEXITCODE."
    }

    if (-not (Test-Path -LiteralPath $appJar)) {
        throw "Application jar was not produced: $appJar"
    }

    New-Item -ItemType Directory -Path $inputDirectory -Force | Out-Null
    Copy-Item -LiteralPath $appJar -Destination $inputDirectory

    $jlinkPath = Join-Path $resolvedJdkHome 'bin\jlink.exe'
    $jmodsDirectory = Join-Path $resolvedJdkHome 'jmods'
    if (-not (Test-Path -LiteralPath $jlinkPath) -or
        -not (Test-Path -LiteralPath $jmodsDirectory)) {
        throw "A complete JDK is required to create the bundled runtime. Missing jlink or jmods in: $resolvedJdkHome"
    }

    # Build the runtime explicitly. This keeps packaging reliable on JDK
    # installations where jpackage does not create its default runtime image.
    $jlinkArguments = @(
        '--module-path', $jmodsDirectory,
        '--add-modules', 'ALL-MODULE-PATH',
        '--strip-debug',
        '--no-header-files',
        '--no-man-pages',
        '--compress=2',
        '--output', $runtimeDirectory
    )

    & $jlinkPath @jlinkArguments
    if ($LASTEXITCODE -ne 0) {
        throw "jlink failed with exit code $LASTEXITCODE."
    }

    $runtimeJava = Join-Path $runtimeDirectory 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $runtimeJava)) {
        throw "jlink did not create the bundled runtime: $runtimeJava"
    }

    $jpackagePath = Join-Path $resolvedJdkHome 'bin\jpackage.exe'
    if (-not (Test-Path -LiteralPath $jpackagePath)) {
        throw "jpackage was not found in the selected JDK: $jpackagePath"
    }

    $jpackageArguments = @(
        '--type', 'app-image',
        '--dest', $imageDirectory,
        '--input', $inputDirectory,
        '--runtime-image', $runtimeDirectory,
        '--main-jar', $appJarName,
        '--main-class', 'io.github.guillermodubon.musicplayer.application.MusicPlayerLauncher',
        '--name', 'GHDMusic',
        '--app-version', '1.0.0',
        '--vendor', 'Guillermo Dubon',
        '--description', 'GHDMusic desktop music player',
        '--icon', $iconFile,
        '--java-options', '-Dfile.encoding=UTF-8'
    )

    & $jpackagePath @jpackageArguments
    if ($LASTEXITCODE -ne 0) {
        throw "jpackage failed with exit code $LASTEXITCODE."
    }

    $runtimeJava = Join-Path $imageDirectory 'GHDMusic\runtime\bin\java.exe'
    if (-not (Test-Path -LiteralPath $runtimeJava)) {
        throw "jpackage created an incomplete application image. The bundled runtime was not found: $runtimeJava"
    }

    Write-Host "Application image created at: $(Join-Path $imageDirectory 'GHDMusic')"
}
finally {
    Pop-Location
}
