# Windows installer

The download-page setup.exe is a branded window, not the Windows Installer wizard. Microsoft Store builds stay on MSIX (`scripts/make-msix.ps1`). The `.msi` is still built so copies from 2.6.x and 2.7.0 can update themselves, and for winget.

## What you see

- Run `YarmiplayTV-<version>-setup.exe`: one card with the logo, the install folder, and ticks for the desktop shortcut, Start menu shortcut, and opening video files. **Install** and **Close**. No Next pages.
- The ticks start from what's already installed. The folder starts as the current install location, or `%LOCALAPPDATA%\YarmiplayTV`.
- A launch update runs the same exe with `--silent --dir <current folder>`. The card stays up and shows copy progress, then the app starts again.
- `/quiet` is the same unattended install (what 2.7.1–2.7.3 would pass).
- Settings in `%APPDATA%\YarmiplayTV` are left as they are.
- An older per-user MSI (UpgradeCode `6F0C2B1E-4E59-4C1B-9B7E-2D1A5C3F8E41`) is removed first, then the files are copied into its folder.

## How it's built

`packageSetupExe` compiles `SetupStub.cs` and appends the app image. The stub extracts it and runs `YarmiplayTV.exe --setup`. `version.json` keeps `file` on the `.msi` and adds `setup` for the exe, which 2.7.4 and later download on launch.
