/// <reference types="node" />
/// <reference types="vitest/config" />
import { defineConfig, type Plugin } from "vite";
import { svelte } from "@sveltejs/vite-plugin-svelte";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const repo = (path: string) => fileURLToPath(new URL(`../${path}`, import.meta.url));

/** The app's version, from the line scripts/bump-version.py edits. */
function appVersion(): string {
  const gradle = readFileSync(repo("app/build.gradle.kts"), "utf8");
  return /^val appVersion = "([^"]+)"/m.exec(gradle)?.[1] ?? "0";
}

/** Each version's patch notes, newest first, from the same metainfo the apps and the website use. */
function releases(): { version: string; date: string; text: string }[] {
  const xml = readFileSync(repo("packaging/linux/com.yarmiplay.TV.metainfo.xml"), "utf8");
  const out: { version: string; date: string; text: string }[] = [];
  const release = /<release version="([^"]+)" date="([^"]+)">([\s\S]*?)<\/release>/g;
  for (let m; (m = release.exec(xml)); ) {
    const text = [...m[3].matchAll(/<p>([\s\S]*?)<\/p>/g)]
      .map((p) => p[1].replace(/\s+/g, " ").replace(/&gt;/g, ">").replace(/&lt;/g, "<").replace(/&amp;/g, "&").trim())
      .join("\n");
    out.push({ version: m[1], date: m[2], text });
  }
  return out;
}

/**
 * `virtual:build-info`: the version, patch notes and MyAnimeList Client ID, fixed at build time; and `build.json` next
 * to the page, which a running page fetches to know a newer version is out.
 */
function buildInfo(): Plugin {
  const id = "virtual:build-info";
  const version = appVersion();
  const built = new Date().toISOString();
  return {
    name: "yarmiplay-build-info",
    resolveId: (source) => (source === id ? "\0" + id : null),
    load(loaded) {
      if (loaded !== "\0" + id) return null;
      const info = { version, built, releases: releases(), malClientId: process.env.MAL_CLIENT_ID ?? "" };
      return `export default ${JSON.stringify(info)};`;
    },
    generateBundle() {
      this.emitFile({ type: "asset", fileName: "build.json", source: JSON.stringify({ version, built }) });
    },
  };
}

export default defineConfig({
  base: "/watch/",
  plugins: [svelte(), buildInfo()],
  build: { target: "es2022", sourcemap: true },
  worker: { format: "es" },
  server: { port: 5173, strictPort: true },
  test: {
    include: ["src/**/*.test.ts"],
    environment: "node",
  },
});
