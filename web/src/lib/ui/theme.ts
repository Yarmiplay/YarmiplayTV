/**
 * The app's themes, as in AppColors.kt: Dark (night blue, the app's own look), Noire
 * (dark purple, as NoireServerTV), Monochrome (true black and white), Light, and Match the system (Light or Dark as the device is set). The colours themselves
 * live in app.css as custom properties under `html[data-theme=…]`; this file picks which set applies.
 */

export type ThemeKey = "dark" | "noire" | "monochrome" | "light" | "system";
export type PaletteKey = "dark" | "noire" | "monochrome" | "light";

export const THEMES: { key: ThemeKey; label: string; description: string }[] = [
  { key: "dark", label: "Dark", description: "Night blue, the app's own look" },
  { key: "noire", label: "Noire", description: "Dark purple, as NoireServerTV" },
  { key: "monochrome", label: "Monochrome", description: "Black and white: true black, darkest on OLED screens" },
  { key: "light", label: "Light", description: "Light backgrounds and dark text" },
  { key: "system", label: "Match the system", description: "Light or dark, as your device is set" },
];

/** Under the theme choices: what no theme changes. */
export const THEME_NOTE = "The player takes the theme too; what's written on the video stays white, on see-through black.";

/** The browser bar's colour for each palette: its background. */
const BAR: Record<PaletteKey, string> = { dark: "#0E1116", noire: "#100C16", monochrome: "#000000", light: "#F3F5F8" };

/** A stored setting as a theme; anything unknown is Dark, as AppTheme.of does. */
export function themeOf(key: string | null | undefined): ThemeKey {
  return THEMES.some((t) => t.key === key) ? (key as ThemeKey) : "dark";
}

/** The palette [theme] draws with; [systemDark] is whether the device is in dark mode. */
export function paletteOf(theme: ThemeKey, systemDark: boolean): PaletteKey {
  if (theme === "system") return systemDark ? "dark" : "light";
  return theme;
}

const systemDarkQuery = () => (typeof matchMedia === "function" ? matchMedia("(prefers-color-scheme: dark)") : null);

/** Recolours the page for [theme] at once, the browser's bar included. */
export function applyTheme(theme: ThemeKey): void {
  const palette = paletteOf(theme, systemDarkQuery()?.matches ?? true);
  document.documentElement.dataset.theme = palette;
  document.querySelector('meta[name="theme-color"]')?.setAttribute("content", BAR[palette]);
}

/** Keeps "Match the system" following the device while it's chosen; returns how to stop. */
export function followSystem(current: () => ThemeKey): () => void {
  const query = systemDarkQuery();
  if (!query) return () => {};
  const changed = () => {
    if (current() === "system") applyTheme("system");
  };
  query.addEventListener("change", changed);
  return () => query.removeEventListener("change", changed);
}
