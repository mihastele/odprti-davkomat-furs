$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$jar = Join-Path $root 'backend\cli\target\opendavki.jar'
if (-not (Test-Path $jar)) {
    throw "opendavki.jar was not built. Run .\build.ps1 first, or download a packaged release."
}
# Pass every argument through untouched: picocli reads -o, --year, --tax-number and the rest.
& java -jar $jar @args
