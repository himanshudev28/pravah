#!/usr/bin/env bash
# Uses the locally cached Gradle distribution (no download).
GRADLE_HOME="$(ls -d "$HOME"/.gradle/wrapper/dists/gradle-8.11.1-bin/*/gradle-8.11.1 2>/dev/null | head -1)"
if [ -z "$GRADLE_HOME" ]; then echo "Gradle 8.11.1 not cached; open the project in Android Studio once." >&2; exit 1; fi
exec "$GRADLE_HOME/bin/gradle" "$@"
