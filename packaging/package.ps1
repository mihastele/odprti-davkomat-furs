param(
    [switch]$SkipTests,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$packageDir = Join-Path $root 'target\package'
$stage = Join-Path $packageDir 'stage'
$jar = Join-Path $root 'backend\cli\target\opendavki.jar'

foreach ($tool in @('jpackage', 'jar', 'java')) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "$tool was not found. Use a JDK 21 that includes jpackage and jlink."
    }
}

if (-not $SkipBuild) {
    & (Join-Path $root 'build.ps1') -SkipTests:$SkipTests
    if ($LASTEXITCODE -ne 0) { throw "the build failed" }
}

if (-not (Test-Path $jar)) { throw "opendavki.jar is missing; run .\build.ps1 first" }
$listing = & jar tf $jar
if (-not ($listing -contains 'web/index.html')) { throw "the jar has no web/index.html; run .\build.ps1 (the Vue build must run before Maven)" }
if (-not ($listing -contains 'schemas/Doh_KDVP_9.xsd')) { throw "the jar has no bundled schemas; rebuild the project" }

[xml]$pom = Get-Content (Join-Path $root 'backend\pom.xml')
$version = [string]$pom.project.version
# jpackage requires a version whose first component is greater than zero (macOS rejects 0.x.y). The artifact keeps the
# project version; this is the bundle metadata version only.
$bundleVersion = '1.0.0'
$architecture = if ($env:PROCESSOR_ARCHITECTURE) { $env:PROCESSOR_ARCHITECTURE.ToLowerInvariant() } else { 'amd64' }
$arch = switch ($architecture) {
    'amd64' { 'x64' }
    'arm64' { 'arm64' }
    'x86' { 'x86' }
    default { $architecture }
}

Remove-Item -Recurse -Force $packageDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $stage | Out-Null
Copy-Item $jar (Join-Path $stage 'opendavki.jar')

Write-Host "Packaging OpenDavki $version for windows-$arch (bundle version $bundleVersion)..."
$modules = 'java.base,java.desktop,java.net.http,java.sql,java.xml,java.logging,java.naming,jdk.httpserver,jdk.crypto.ec,jdk.unsupported,jdk.jsobject,jdk.xml.dom'
& jpackage `
    --type app-image `
    --name OpenDavki `
    --app-version $bundleVersion `
    --input $stage `
    --main-jar opendavki.jar `
    --main-class si.opendavki.cli.Main `
    --dest $packageDir `
    --add-modules $modules `
    --java-options '-Dfile.encoding=UTF-8'
if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }

$app = Join-Path $packageDir 'OpenDavki'
Copy-Item (Join-Path $root 'packaging\RUNTIME-README.txt') (Join-Path $app 'README.txt')
Copy-Item (Join-Path $root 'packaging\THIRD-PARTY-NOTICES.txt') (Join-Path $app 'THIRD-PARTY-NOTICES.txt')

# Prove the launcher starts with its own runtime and finds the bundled UI and schemas, from outside the repository.
$originalHome = $env:OPENDAVKI_HOME
try {
    $env:OPENDAVKI_HOME = Join-Path $packageDir 'smoke-home'
    $probe = Start-Process -FilePath (Join-Path $app 'OpenDavki.exe') -ArgumentList 'paths' -Wait -PassThru -WindowStyle Hidden
    if ($probe.ExitCode -ne 0) { throw "the packaged launcher failed its paths check (exit $($probe.ExitCode))" }

    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
    $listener.Stop()
    $server = Start-Process -FilePath (Join-Path $app 'OpenDavki.exe') -ArgumentList 'serve', '--port', "$port", '--no-open' `
        -WorkingDirectory $env:TEMP -PassThru -WindowStyle Hidden
    try {
        $served = $false
        for ($i = 0; $i -lt 40; $i++) {
            Start-Sleep -Milliseconds 250
            try {
                $page = Invoke-WebRequest -Uri "http://127.0.0.1:$port/" -UseBasicParsing -TimeoutSec 2
                if ($page.StatusCode -eq 200 -and $page.Content -match 'OpenDavki') { $served = $true; break }
            } catch { }
        }
        if (-not $served) { throw "the packaged launcher did not serve the bundled UI on 127.0.0.1:$port" }
    } finally {
        if (-not $server.HasExited) { Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue }
    }
} finally {
    $env:OPENDAVKI_HOME = $originalHome
}

$zip = Join-Path $packageDir "OpenDavki-$version-windows-$arch.zip"
Compress-Archive -Path $app -DestinationPath $zip
$hash = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLowerInvariant()
"$hash  $(Split-Path -Leaf $zip)" | Set-Content (Join-Path $packageDir 'SHA256SUMS')

# The JavaFX shell is a second, native-window client over the same local server and UI.
$desktopJar = Join-Path $root 'backend\desktop\target\opendavki-desktop.jar'
if (-not (Test-Path $desktopJar)) { throw "the JavaFX desktop jar is missing; run .\build.ps1" }
$desktopStage = Join-Path $packageDir 'desktop-stage'
New-Item -ItemType Directory -Force $desktopStage | Out-Null
Copy-Item $desktopJar (Join-Path $desktopStage 'opendavki-desktop.jar')
Write-Host "Packaging OpenDavki Desktop $version for windows-$arch..."
& jpackage `
    --type app-image `
    --name OpenDavkiDesktop `
    --app-version $bundleVersion `
    --input $desktopStage `
    --main-jar opendavki-desktop.jar `
    --main-class si.opendavki.desktop.Launcher `
    --dest $packageDir `
    --add-modules $modules `
    --java-options '-Dfile.encoding=UTF-8'
if ($LASTEXITCODE -ne 0) { throw "jpackage failed for the JavaFX desktop shell" }

$desktopApp = Join-Path $packageDir 'OpenDavkiDesktop'
Copy-Item (Join-Path $root 'packaging\RUNTIME-DESKTOP-README.txt') (Join-Path $desktopApp 'README.txt')
Copy-Item (Join-Path $root 'packaging\THIRD-PARTY-NOTICES.txt') (Join-Path $desktopApp 'THIRD-PARTY-NOTICES.txt')
try {
    $env:OPENDAVKI_HOME = Join-Path $packageDir 'smoke-home-desktop'
    $desktopProbe = Start-Process -FilePath (Join-Path $desktopApp 'OpenDavkiDesktop.exe') -ArgumentList '--smoke' `
        -WorkingDirectory $env:TEMP -Wait -PassThru -WindowStyle Hidden
    if ($desktopProbe.ExitCode -ne 0) { throw "the packaged JavaFX shell failed its smoke test (exit $($desktopProbe.ExitCode))" }
} finally {
    $env:OPENDAVKI_HOME = $originalHome
}

$desktopZip = Join-Path $packageDir "OpenDavkiDesktop-$version-windows-$arch.zip"
Compress-Archive -Path $desktopApp -DestinationPath $desktopZip
$desktopHash = (Get-FileHash $desktopZip -Algorithm SHA256).Hash.ToLowerInvariant()
Add-Content (Join-Path $packageDir 'SHA256SUMS') "$desktopHash  $(Split-Path -Leaf $desktopZip)"

Write-Host ""
Write-Host "Packaged: $zip"
Write-Host "SHA256:   $hash"
Write-Host "Try it:   $app\OpenDavki.exe"
Write-Host "Desktop:  $desktopZip"
Write-Host "SHA256:   $desktopHash"
