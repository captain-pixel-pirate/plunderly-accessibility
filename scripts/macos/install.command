#!/bin/bash
# Plunderly Accessibility Companion — macOS installer.
# Run this from the folder that holds plunderly.jar (double-click in Finder, or
# run from a terminal). It installs the companion and loads it into the game
# through Java's assistive-technology hook. Safe to re-run.
set -euo pipefail

INSTALL_DIR="$HOME/Library/Application Support/Plunderly"
APP_DIR="/Applications/Puzzle Pirates.app/Contents/app"
EXTRA="$APP_DIR/extra.txt"
SELF="$(cd "$(dirname "$0")" && pwd)"
JAR="$INSTALL_DIR/plunderly.jar"

[ -f "$SELF/plunderly.jar" ] || { echo "ERROR: plunderly.jar must sit next to this installer." >&2; exit 1; }
[ -d "$APP_DIR" ]            || { echo "ERROR: Puzzle Pirates not found at /Applications." >&2; exit 1; }

# The game's launcher (Getdown) only reads extra.txt from inside the app bundle,
# so we must write there. Modern macOS ("App Management" protection) blocks one
# app from changing another app's files, which makes the write fail with a
# cryptic "Operation not permitted". Preflight it and give real instructions.
PROBE="$APP_DIR/.plunderly-writetest"
if ! ( : > "$PROBE" ) 2>/dev/null; then
  TERM_APP="${TERM_PROGRAM:-your terminal app}"
  case "$TERM_APP" in Apple_Terminal) TERM_APP="Terminal";; iTerm.app) TERM_APP="iTerm";; esac
  cat >&2 <<EOF
ERROR: macOS blocked the installer from writing inside Puzzle Pirates.

Modern macOS stops one app from changing another app's files ("App Management").
To let the installer add the accessibility startup file, grant $TERM_APP permission:

  1. Open  System Settings > Privacy & Security > App Management
  2. Turn ON the switch for "$TERM_APP"
  3. Quit and reopen $TERM_APP (the change only applies after a restart)
  4. Run this installer again

You can turn that switch back off once the install finishes.
EOF
  exit 1
fi
rm -f "$PROBE"

# extra.txt is an optional game file; a clean install has none. Create it.
[ -f "$EXTRA" ] || : > "$EXTRA"

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
