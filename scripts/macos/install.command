#!/bin/bash
# Plunderly Accessibility Companion — macOS installer.
# Run this from the folder that holds plunderly.jar (double-click in Finder, or
# run from a terminal). It installs the companion and loads it into the game
# through Java's assistive-technology hook. Safe to re-run.
set -euo pipefail

INSTALL_DIR="$HOME/Library/Application Support/Plunderly"
EXTRA="/Applications/Puzzle Pirates.app/Contents/app/extra.txt"
SELF="$(cd "$(dirname "$0")" && pwd)"
JAR="$INSTALL_DIR/plunderly.jar"

[ -f "$SELF/plunderly.jar" ] || { echo "ERROR: plunderly.jar must sit next to this installer." >&2; exit 1; }
[ -f "$EXTRA" ]             || { echo "ERROR: Puzzle Pirates not found at /Applications." >&2; exit 1; }

# Copy the jar to a stable spot (extra.txt points at it by absolute path).
mkdir -p "$INSTALL_DIR"
cp "$SELF/plunderly.jar" "$JAR"

# Back up extra.txt once, then drop any old Plunderly boot-classpath line.
[ -f "$EXTRA.plunderly-bak" ] || cp "$EXTRA" "$EXTRA.plunderly-bak"
grep -vi 'Xbootclasspath/a:.*plunderly' "$EXTRA" > "$EXTRA.tmp" || true
mv "$EXTRA.tmp" "$EXTRA"
[ -n "$(tail -c1 "$EXTRA")" ] && printf '\n' >> "$EXTRA"   # ensure trailing newline

# Register Probe (append to any existing list so we don't clobber a real screen
# reader), then add our jar to the boot class path so the JVM can load it.
ASSIST='^-Djavax\.accessibility\.assistive_technologies='
if grep -q "$ASSIST" "$EXTRA"; then
  grep "$ASSIST" "$EXTRA" | grep -q Probe || sed -i '' -E "s|(${ASSIST}.*)|\1,Probe|" "$EXTRA"
else
  echo "-Djavax.accessibility.assistive_technologies=Probe" >> "$EXTRA"
fi
echo "-Xbootclasspath/a:$JAR" >> "$EXTRA"

echo "Installed. Quit Puzzle Pirates if it's running, then launch it again."
echo "Reports are saved to: $INSTALL_DIR"
