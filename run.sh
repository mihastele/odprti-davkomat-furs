#!/usr/bin/env bash
# Start OpenDavki from the repository. With no arguments this opens the local web interface.
set -euo pipefail
cd "$(dirname "$0")"
jar="backend/cli/target/opendavki.jar"
if [ ! -f "$jar" ]; then
  echo "opendavki.jar was not built. Run ./build.sh first, or download a packaged release." >&2
  exit 1
fi
exec java -jar "$jar" "$@"
