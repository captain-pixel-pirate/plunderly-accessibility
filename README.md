# Plunderly Accessibility Companion

Plunderly Accessibility Companion is a small helper app for **Puzzle Pirates**.
It reads the on-screen **Duty Report** and turns it into clean, easy-to-copy
output: per-pirate stations, ratings, bonus breakdowns, a saved history, and a
simple dashboard.

## How it works

Puzzle Pirates already shares some on-screen information with accessibility
tools, like screen readers. This companion uses that same built-in access to
read the Duty Report and show it in a clearer window.

The install is intentionally minimal: it only adds to the game's `extra.txt` file,
a small startup settings needed to open the companion with the game. It
does **not**:

- patch, modify, or repackage the game,
- read or alter network traffic,
- change anything about gameplay, or
- automate anything

It only reads information the game already makes available for accessibility and
presents it in a more readable way. The `extra.txt` change is reversible, and the
installer backs up your original file before editing it.

### No gameplay advantage

This accessibility companion gives you **no edge in the game**. It cannot touch gameplay,
timing, puzzle inputs, or anything you do while playing.

All it does is take the bonus scores already printed on a Duty Report, that are small and hard to read quickly. It
also keeps a saved history so you can look back later. Every count it shows was
already on your screen.

## Installation

The easiest option is to download the prebuilt zip for your platform from the
[**latest release**](../../releases/latest). Each zip includes the companion
(`plunderly.jar`) and the installer. Unzip it, run the installer, and restart the
game.

### macOS

1. Download **`plunderly-macos.zip`** and unzip it.
2. Double-click **`install.command`**.
   - macOS may block it the first time with an "unidentified developer" warning.
     If that happens, **right-click `install.command` -> Open**, then confirm.
     You only need to do this once.
3. Quit Puzzle Pirates if it's running, then launch it again.

### Windows

1. Launch Puzzle Pirates at least once (so its config folder exists), then quit.
2. Download **`plunderly-windows.zip`** and unzip it.
3. Right-click **`Install-Plunderly.ps1`** -> **Run with PowerShell** (or run
   `powershell -ExecutionPolicy Bypass -File .\Install-Plunderly.ps1`).
4. Launch Puzzle Pirates again.

The companion window appears alongside the game. Because the only install change
is in `extra.txt`, you need to fully restart Puzzle Pirates after installing or
updating so the game can read that file again. The installers are safe to run
again if needed.

### Building from source instead

To build it yourself, you need at least **JDK 21**:

```bash
./scripts/build.sh      # compiles src/ and produces dist/plunderly.jar
```

Then run the installer for your platform from `scripts/`, making sure
`plunderly.jar` is **next to the installer script**. The installer expects the
jar beside it.

## Uninstall

Run the matching uninstaller. It reverses the install and restores your backed-up
`extra.txt`:

- **macOS:** `./scripts/macos/uninstall.command`
- **Windows:** `Uninstall-Plunderly.ps1`

## License

[MIT](LICENSE).
