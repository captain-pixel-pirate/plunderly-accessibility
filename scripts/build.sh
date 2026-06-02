#!/bin/bash
# Compile the companion app, default-package assistive-tech hook, tree probe,
# and standalone test, then package them into dist/plunderly.jar.
#
# JDK selection
# -------------
# We always compile with `--release 21` (see the javac line below): the game
# bundles its own JRE (currently 25), and targeting release 21 keeps the classes
# loadable there while letting us build on anything reasonably modern. Because
# `--release 21` only requires a *compiler* of version 21 or newer, ANY JDK >= 21
# works to build this — it does not have to be exactly 21.
#
# This script resolves a JDK in the following order, using the first that works:
#   1. $JDK              — explicit override; point it at a JDK's bin/ directory,
#                          e.g.  JDK=/path/to/jdk-21/bin ./scripts/build.sh
#                          (trusted as-is; lets you force a specific toolchain)
#   2. /usr/libexec/java_home -v 21   — macOS: a JDK 21 registered with the OS
#   3. Homebrew openjdk@21            — /opt/homebrew (Apple silicon) or
#                                       /usr/local (Intel) install location
#   4. $JAVA_HOME/bin                 — a JDK pointed to by the standard env var
#   5. javac on $PATH                 — whatever compiler is already on PATH
# The chosen compiler's version is then checked to be >= 21, with a clear error
# if not. To use a JDK this doesn't find automatically, set $JDK (option 1).
set -euo pipefail

# Print the major version of the javac in a given bin dir (empty on failure).
# `javac -version` prints e.g. "javac 21.0.10"; we take the leading "21".
javac_major() {
  "$1/javac" -version 2>&1 | awk 'NR==1{print $2}' | cut -d. -f1
}

resolve_jdk() {
  # 1. Explicit override wins, no questions asked.
  if [ -n "${JDK:-}" ]; then printf '%s\n' "$JDK"; return; fi

  # 2. macOS: ask the OS for a registered JDK 21.
  if [ -x /usr/libexec/java_home ]; then
    local home
    if home="$(/usr/libexec/java_home -v 21 2>/dev/null)" && [ -n "$home" ]; then
      printf '%s\n' "$home/bin"; return
    fi
  fi

  # 3. Homebrew openjdk@21 (Apple silicon, then Intel).
  if [ -x /opt/homebrew/opt/openjdk@21/bin/javac ]; then
    printf '%s\n' /opt/homebrew/opt/openjdk@21/bin; return
  fi
  if [ -x /usr/local/opt/openjdk@21/bin/javac ]; then
    printf '%s\n' /usr/local/opt/openjdk@21/bin; return
  fi

  # 4. $JAVA_HOME, if it actually holds a JDK (has javac, not just a JRE).
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
    printf '%s\n' "$JAVA_HOME/bin"; return
  fi

  # 5. Anything on PATH.
  if command -v javac >/dev/null 2>&1; then
    dirname "$(command -v javac)"; return
  fi

  printf '\n'  # nothing found
}

JDK="$(resolve_jdk)"
if [ -z "$JDK" ] || [ ! -x "$JDK/javac" ]; then
  echo "ERROR: no JDK found. Install a JDK 21 (or newer), or set \$JDK to its bin/ dir:" >&2
  echo "       JDK=/path/to/jdk/bin ./scripts/build.sh" >&2
  exit 1
fi

MAJOR="$(javac_major "$JDK")"
if [ -z "$MAJOR" ] || [ "$MAJOR" -lt 21 ] 2>/dev/null; then
  echo "ERROR: javac at $JDK is version '${MAJOR:-unknown}', but 21+ is required" >&2
  echo "       (we compile with --release 21). Set \$JDK to a JDK 21+ bin/ dir." >&2
  exit 1
fi

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
OUT="$ROOT/build"
DIST="$ROOT/dist"
JAR="$DIST/plunderly.jar"

echo "Using javac: $JDK/javac"
"$JDK/javac" -version

rm -rf "$OUT"
mkdir -p "$OUT"
find "$ROOT/src" -name '*.java' -print0 | sort -z | xargs -0 "$JDK/javac" --release 21 -d "$OUT"

echo "Compiled to: $OUT"
ls -l "$OUT"

# Package a single distributable jar. The assistive-tech hook names the
# default-package Probe class, which sits at the jar root, so end users put this
# one file on the boot class path instead of pointing at a build/ directory.
mkdir -p "$DIST"
rm -f "$JAR"
"$JDK/jar" --create --file "$JAR" -C "$OUT" .

echo "Packaged jar: $JAR"
ls -l "$JAR"
