$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$issFile = Join-Path $PSScriptRoot 'GHDMusic.iss'

& (Join-Path $PSScriptRoot 'build-app-image.ps1')
if ($LASTEXITCODE -ne 0) {
    throw "Application image build failed with exit code $LASTEXITCODE."
}

$runtimeJava = Join-Path $projectRoot 'target\jpackage\app-image\GHDMusic\runtime\bin\java.exe'
if (-not (Test-Path -LiteralPath $runtimeJava)) {
    throw "The application image has no bundled Java runtime: $runtimeJava"
}

$iscc = Get-Command 'ISCC.exe' -ErrorAction SilentlyContinue
if ($null -eq $iscc) {
    throw 'Inno Setup compiler was not found. Install Inno Setup 6 and add ISCC.exe to PATH.'
}

Push-Location $projectRoot
try {
    & $iscc.Source $issFile
    if ($LASTEXITCODE -ne 0) {
        throw "Inno Setup failed with exit code $LASTEXITCODE."
    }

    Write-Host 'Installer created under target\jpackage\installer.'
}
finally {
    Pop-Location
}
