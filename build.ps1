param(
    [switch]$SkipTests,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$MavenArgs
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
    throw "npm was not found. Install Node.js 22, or download a packaged release instead of building from source."
}

Write-Host "Building the web interface..."
& npm --prefix "$root\frontend" ci
if ($LASTEXITCODE -ne 0) { throw "npm ci failed" }
& npm --prefix "$root\frontend" run build
if ($LASTEXITCODE -ne 0) { throw "the web build failed" }

$maven = if (Test-Path "$root\mvnw.cmd") { "$root\mvnw.cmd" } else { "mvn" }
$mavenArgsList = @('-f', "$root\backend\pom.xml", '-B', 'clean')
if (-not $SkipTests) { $mavenArgsList += 'install' } else { $mavenArgsList += '-DskipTests', 'install' }
$mavenArgsList += $MavenArgs

Write-Host "Building the Java application..."
& $maven @mavenArgsList
if ($LASTEXITCODE -ne 0) { throw "the Java build failed" }

Write-Host ""
Write-Host "Built: $root\backend\cli\target\opendavki.jar"
Write-Host "Run it with: .\run.ps1"
