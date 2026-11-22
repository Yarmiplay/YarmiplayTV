// The theme before the first paint, so the page never flashes the wrong colours (see src/lib/ui/theme.ts). A file
// of its own rather than inline, so the page's Content-Security-Policy allows scripts from the site only.
(function () {
  var key = "dark";
  try { key = localStorage.getItem("ytv:app_theme") || "dark"; } catch (e) {}
  if (key === "system") key = matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
  var known = { dark: 1, noire: 1, monochrome: 1, light: 1, ember: 1, snow: 1, grass: 1, sky: 1 };
  if (!known[key]) key = "dark";
  document.documentElement.dataset.theme = key;
  var bar = {
    dark: "#0E1116", noire: "#100C16", monochrome: "#000000", light: "#F3F5F8",
    ember: "#14110E", snow: "#75898A", grass: "#538532", sky: "#41B1E9",
  }[key];
  document.querySelector('meta[name="theme-color"]').setAttribute("content", bar);
})();
