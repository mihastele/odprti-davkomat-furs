#!/usr/bin/env bash
# Build a self-contained app image for the current platform. Run on macOS or Linux.
set -euo pipefail
cd "$(dirname "$0")/.."
root="$(pwd)"
package_dir="$root/target/package"
stage="$package_dir/stage"
jar="$root/backend/cli/target/opendavki.jar"
version="$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' backend/pom.xml | head -n 1)"
app_version="${version%-SNAPSHOT}"

skip_build=0
skip_tests=0
for arg in "$@"; do
  case "$arg" in
    --skip-build) skip_build=1 ;;
    --skip-tests) skip_tests=1 ;;
    *) echo "Unknown option: $arg" >&2; exit 2 ;;
  esac
done

for tool in jpackage jar java; do
  command -v "$tool" >/dev/null 2>&1 || { echo "$tool was not found; use a JDK 21 with jpackage and jlink." >&2; exit 1; }
done

if [ "$skip_build" -eq 0 ]; then
  if [ "$skip_tests" -eq 1 ]; then
    ./build.sh -DskipTests
  else
    ./build.sh
  fi
fi

[ -f "$jar" ] || { echo "opendavki.jar is missing; run ./build.sh first" >&2; exit 1; }
jar tf "$jar" | grep -qx 'web/index.html' || { echo "the jar has no web/index.html; run ./build.sh" >&2; exit 1; }
jar tf "$jar" | grep -qx 'schemas/Doh_KDVP_9.xsd' || { echo "the jar has no bundled schemas; rebuild" >&2; exit 1; }

uname_s="$(uname -s)"
case "$uname_s" in
  Darwin) platform="macos"; arch="$(uname -m)"; [ "$arch" = "arm64" ] || arch="x64" ;;
  Linux) platform="linux"; arch="$(uname -m)"; [ "$arch" = "x86_64" ] && arch="x64" ;;
  *) echo "Unsupported platform: $uname_s" >&2; exit 1 ;;
esac

rm -rf "$package_dir"
mkdir -p "$stage"
cp "$jar" "$stage/opendavki.jar"

echo "Packaging OpenDavki $app_version for $platform-$arch..."
modules='java.base,java.desktop,java.net.http,java.sql,java.xml,java.logging,java.naming,jdk.httpserver,jdk.crypto.ec,jdk.unsupported'
jpackage \
  --type app-image \
  --name OpenDavki \
  --app-version "$app_version" \
  --input "$stage" \
  --main-jar opendavki.jar \
  --main-class si.opendavki.cli.Main \
  --dest "$package_dir" \
  --add-modules "$modules" \
  --java-options '-Dfile.encoding=UTF-8'

app="$package_dir/OpenDavki"
if [ "$platform" = "macos" ]; then
  app="$package_dir/OpenDavki.app"
  # jpackage names the bundle OpenDavki.app on macOS; the executable lives inside Contents/MacOS.
  cp packaging/RUNTIME-README.txt "$app/Contents/MacOS/README.txt"
  cp packaging/THIRD-PARTY-NOTICES.txt "$app/Contents/MacOS/THIRD-PARTY-NOTICES.txt"
else
  cp packaging/RUNTIME-README.txt "$app/README.txt"
  cp packaging/THIRD-PARTY-NOTICES.txt "$app/THIRD-PARTY-NOTICES.txt"
fi

# Prove the launcher starts from outside the repository with its own runtime.
probe_home="$package_dir/smoke-home"
if [ "$platform" = "macos" ]; then
  OPENDAVKI_HOME="$probe_home" "$app/Contents/MacOS/OpenDavki" paths >/dev/null
  artifact="$package_dir/OpenDavki-$version-macos-$arch.zip"
  (cd "$package_dir" && ditto -c -k --sequesterRsrc --keepParent OpenDavki.app "$artifact")
else
  OPENDAVKI_HOME="$probe_home" "$app/bin/OpenDavki" paths >/dev/null
  artifact="$package_dir/OpenDavki-$version-linux-$arch.tar.gz"
  tar -C "$package_dir" -czf "$artifact" OpenDavki
fi

if command -v sha256sum >/dev/null 2>&1; then
  hash="$(sha256sum "$artifact" | awk '{print $1}')"
else
  hash="$(shasum -a 256 "$artifact" | awk '{print $1}')"
fi
echo "$hash  $(basename "$artifact")" > "$package_dir/SHA256SUMS"
echo
echo "Packaged: $artifact"
echo "SHA256:   $hash"
