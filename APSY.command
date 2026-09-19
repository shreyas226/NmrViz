#!/bin/bash
# Double-click this file to launch APSY.
#
# The app must run with this folder as the working directory: it looks for
# .venv/bin/python and nmr_backend.py by relative path, and loading a dataset
# fails confusingly if it is started from anywhere else. cd-ing to the script's
# own location means the launcher keeps working even if the folder is moved or
# renamed.
cd "$(dirname "$0")" || exit 1

JAVA=java
command -v "$JAVA" >/dev/null 2>&1 || JAVA=/usr/bin/java
command -v "$JAVA" >/dev/null 2>&1 || \
    JAVA=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home/bin/java

if [ ! -x "$JAVA" ] && ! command -v "$JAVA" >/dev/null 2>&1; then
    echo "Java not found. Install a JDK, then run this again."
    read -r -p "Press Return to close."
    exit 1
fi

exec "$JAVA" -cp . NmrVisualizer
