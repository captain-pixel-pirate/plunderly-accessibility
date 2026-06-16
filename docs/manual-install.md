# Manual installation

Use this guide if you would rather install Plunderly Accessibility Companion by
hand instead of running one of the platform installer scripts. The release page
has three downloads:

- `plunderly.jar` for manual installation
- `plunderly-windows.zip` for the Windows installer
- `plunderly-macos.zip` for the macOS installer

The manual install does the same basic setup as the installer: put
`plunderly.jar` somewhere stable, create Puzzle Pirates' `extra.txt` startup
file, and add the two Java startup lines that load the companion.

## Step 1: Download the manual jar

Download `plunderly.jar` from the
[latest release](../../../releases/latest).

What this does: `plunderly.jar` is the companion app. The zip files are still
available for users who want the automatic installer, but manual installation
only needs this one jar file.

## Step 2: Create a stable Plunderly folder

Create the folder for your operating system:

### Windows

```text
%APPDATA%\Plunderly
```

For most users, that expands to something like:

```text
C:\Users\YOURNAME\AppData\Roaming\Plunderly
```

### macOS

```text
~/Library/Application Support/Plunderly
```

For most users, that expands to something like:

```text
/Users/YOURNAME/Library/Application Support/Plunderly
```

What this does: the game needs an exact path to `plunderly.jar`. Putting the jar
in a stable folder means the path will keep working after you close your
Downloads folder or delete the release download.

## Step 3: Move `plunderly.jar` into that folder

Move the downloaded `plunderly.jar` into the Plunderly folder you created.

### Windows

The final file should be:

```text
C:\Users\YOURNAME\AppData\Roaming\Plunderly\plunderly.jar
```

### macOS

The final file should be:

```text
/Users/YOURNAME/Library/Application Support/Plunderly/plunderly.jar
```

What this does: this gives Puzzle Pirates a permanent file location to load when
it starts.

## Step 4: Find the Puzzle Pirates startup folder

This is the folder where Puzzle Pirates reads `extra.txt`.

### Windows

Most newer installs use:

```text
%LOCALAPPDATA%\Puzzle Pirates\app
```

For most users, that expands to something like:

```text
C:\Users\YOURNAME\AppData\Local\Puzzle Pirates\app
```

Older installs may use:

```text
%APPDATA%\Three Rings Design\Puzzle Pirates
```

For most users, that expands to something like:

```text
C:\Users\YOURNAME\AppData\Roaming\Three Rings Design\Puzzle Pirates
```

The correct folder usually contains a `code` folder or a `getdown.txt` file.

### macOS

Open:

```text
/Applications/Puzzle Pirates.app
```

Right-click the app, choose **Show Package Contents**, then open:

```text
Contents/app
```

The full folder is:

```text
/Applications/Puzzle Pirates.app/Contents/app
```

What this does: `extra.txt` has to live beside the game's startup files. Puzzle
Pirates reads this file during launch and passes the lines inside it to Java.

## Step 5: Create `extra.txt`

Inside the Puzzle Pirates startup folder from step 4, create a plain-text file
named:

```text
extra.txt
```

What this does: `extra.txt` is the standard game startup-settings file. On a
normal install it may not exist yet, so creating it is expected.

## Step 6: Add the Plunderly startup lines

Open `extra.txt` in a plain-text editor, such as Notepad on Windows or TextEdit
in plain-text mode on macOS.

### Windows

Add these two lines, replacing `YOURNAME` with your Windows username:

```text
-Djavax.accessibility.assistive_technologies=Probe
-Xbootclasspath/a:C:\Users\YOURNAME\AppData\Roaming\Plunderly\plunderly.jar
```

### macOS

Add these two lines, replacing `YOURNAME` with your macOS username:

```text
-Djavax.accessibility.assistive_technologies=Probe
-Xbootclasspath/a:/Users/YOURNAME/Library/Application Support/Plunderly/plunderly.jar
```

What this does:

- The first line tells Java to start the Plunderly accessibility hook, `Probe`.
- The second line tells Java where to find `plunderly.jar`.

## Step 7: Save the file

Save `extra.txt` as plain text.

### Windows

Make sure the file is named exactly:

```text
extra.txt
```

Not:

```text
extra.txt.txt
```

### macOS

If macOS blocks you from saving inside the Puzzle Pirates app, grant your editor
or terminal permission under:

```text
System Settings > Privacy & Security > App Management
```

Then quit and reopen that editor or terminal, and save the file again.

What this does: the file has to be saved in the game startup folder before the
game launches. If the path or file name is slightly wrong, Puzzle Pirates will
not load the companion.

## Step 8: Restart Puzzle Pirates

Launch Puzzle Pirates again.

What this does: Puzzle Pirates reads `extra.txt`, loads `plunderly.jar`, and the
Plunderly companion window should appear alongside the game.

## Manual uninstall

1. Fully quit Puzzle Pirates.
2. Open the same `extra.txt` file you created during installation.
3. Delete these two lines:

   ```text
   -Djavax.accessibility.assistive_technologies=Probe
   -Xbootclasspath/a:...
   ```

4. Save `extra.txt`.
5. Delete `plunderly.jar` from the Plunderly folder.

Saved reports can be left in the Plunderly folder or deleted manually.
