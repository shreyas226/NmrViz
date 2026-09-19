#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/dist/APSY.app"
RESOURCES="$APP/Contents/Resources"
CLASSES="$RESOURCES/classes"

command -v javac >/dev/null 2>&1 || {
    echo "javac is required. Install a JDK 17 or newer." >&2
    exit 1
}
command -v python3 >/dev/null 2>&1 || {
    echo "python3 is required." >&2
    exit 1
}

rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$CLASSES"
cp "$ROOT/packaging/Info.plist" "$APP/Contents/Info.plist"
cp "$ROOT/packaging/APSY-launcher" "$APP/Contents/MacOS/APSY"
chmod +x "$APP/Contents/MacOS/APSY"

javac -d "$CLASSES" "$ROOT/src/NmrVisualizer.java"
cp "$ROOT"/src/*.py "$RESOURCES/"

python3 -m venv "$RESOURCES/.venv"
"$RESOURCES/.venv/bin/python" -m pip install --upgrade pip
"$RESOURCES/.venv/bin/python" -m pip install -r "$ROOT/requirements.txt"

echo "Created $APP"