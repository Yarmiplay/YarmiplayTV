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

Until the custom bootstrapper fat-binary lands, CI ships jpackage’s `.exe` as `YarmiplayTV-<version>-setup.exe` (release.yml / build.yml) and keeps the MSI on the GitHub release **and** on the download site for `version.json` (in-app updates). SignPath signs the setup `.exe`, the `.msi`, and the portable zip launcher.

Download page order for Windows: Microsoft Store, then setup `.exe`, then portable `.zip` — no MSI button or SHA-256 row. `version.json` still points Windows updates at `YarmiplayTV.msi` so 2.6.x / 2.7.0 installers keep working (`msiexec /passive`). Do not point updates at `.exe` until every shipped updater uses jpackage’s `/quiet` (2.7.0 wrongly passed `--silent --relaunch`).
