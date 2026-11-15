#!/usr/bin/env bash
# Build the Vue interface and the self-contained jar from any directory.
set -euo pipefail
cd "$(dirname "$0")"

if ! command -v npm >/dev/null 2>&1; then
  echo "npm was not found. Install Node.js 22, or download a packaged release instead of building from source." >&2
  exit 1
fi

echo "Building the web interface..."
npm --prefix frontend ci
npm --prefix frontend run build

echo "Building the Java application..."
if [ -x ./mvnw ]; then
  ./mvnw -f backend/pom.xml -B clean install "$@"
else
  mvn -f backend/pom.xml -B clean install "$@"
fi

echo
echo "Built: backend/cli/target/opendavki.jar"
echo "Run it with: ./run.sh"
