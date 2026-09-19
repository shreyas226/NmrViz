#!/bin/bash
# Double-click this file to launch APSY.
#
# The Java sources live in src/, the venv at the project root and the sample
# spectra under datasets/, so the three -D properties below tell the app where
# each one is. cd-ing to the script's own location means the launcher keeps
# working even if the folder is moved or renamed.
cd "$(dirname "$0")/.." || exit 1
ROOT="$(pwd)"

JAVA=java
command -v "$JAVA" >/dev/null 2>&1 || JAVA=/usr/bin/java
command -v "$JAVA" >/dev/null 2>&1 || \
    JAVA=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home/bin/java

if [ ! -x "$JAVA" ] && ! command -v "$JAVA" >/dev/null 2>&1; then
    echo "Java not found. Install a JDK, then run this again."
    read -r -p "Press Return to close."
    exit 1
fi

# Recompile when the source is newer than the last build, so double-clicking
# after an edit does not silently run a stale class file.
CLASSES="$ROOT/build/classes"
if [ ! -f "$CLASSES/NmrVisualizer.class" ] || \
   [ "$ROOT/src/NmrVisualizer.java" -nt "$CLASSES/NmrVisualizer.class" ]; then
    echo "Compiling..."
    mkdir -p "$CLASSES"
    javac -d "$CLASSES" "$ROOT/src/NmrVisualizer.java" || {
        echo "Compilation failed."
        read -r -p "Press Return to close."
        exit 1
    }
fi

exec "$JAVA" \
    -Dapsy.resource.dir="$ROOT/src" \
    -Dapsy.venv.dir="$ROOT" \
    -Dapsy.data.dir="$ROOT/datasets" \
    -cp "$CLASSES" NmrVisualizer
