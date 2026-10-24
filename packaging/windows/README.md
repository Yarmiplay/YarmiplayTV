# Windows branded installer (2.7.0)

Download-page and winget installs use a **branded `.exe`**, not the stock jpackage MSI wizard.
Microsoft Store builds stay on MSIX (`desktop/msix`, `scripts/make-msix.ps1`).

## Goals

- Same look as the in-app update card (`LaunchUpdateWindow`: logo, dark card, accent progress).
- `/S` / `--silent` still shows that small progress window (not a fully invisible install).
- Upgrade from current per-user MSI installs via UpgradeCode `6F0C2B1E-4E59-4C1B-9B7E-2D1A5C3F8E41`.
- Keep `%APPDATA%\YarmiplayTV` settings as-is.
- Auto-update: `WindowsUpdater` runs `YarmiplayTV-<version>-setup.exe --silent --relaunch`.

## Layout

| Path | Role |
|---|---|
| `desktop` `TargetFormat.Exe` + `Msi` | jpackage payloads (Exe preferred for new installs; MSI kept for migration) |
| `com.yarmiplaytv.desktop.WindowsSetupUi` | Branded progress window for silent/UI installs |
| `com.yarmiplaytv.desktop.WindowsUpdater` | Downloads `.exe` (falls back to `.msi`) and runs it after exit |
| SignPath / `release.yml` | Sign `YarmiplayTV-*-setup.exe` (and portable zip); winget points at the exe |

## Flags (installer)

- `--silent` / `/S` — unattended; still shows the branded progress card
- `--relaunch` — start the app again after a successful install
- `--dir <path>` — optional install directory (default: per-user location matching today’s MSI)

## Signing / CI

Until the custom bootstrapper fat-binary lands, CI can ship jpackage’s `.exe` under the setup name and keep MSI for UpgradeCode tests. SignPath artifact config should list the `.exe` next to (then instead of) the `.msi`.
