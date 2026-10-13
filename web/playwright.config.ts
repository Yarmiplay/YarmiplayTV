/// <reference types="node" />
import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "e2e",
  timeout: 60_000,
  retries: 0,
  use: {
    baseURL: "http://localhost:5173/watch/",
    // Google Chrome rather than Playwright's Chromium, which leaves out H.264, HEVC and AAC.
    channel: process.env.PLAYWRIGHT_CHANNEL ?? "chrome",
    headless: process.env.HEADED ? false : true,
    launchOptions: { args: ["--autoplay-policy=no-user-gesture-required", "--enable-features=PlatformHEVCDecoderSupport"] },
  },
  webServer: {
    // E2E_PREVIEW=1 tests the production build (npm run build first), as CI does.
    command: process.env.E2E_PREVIEW ? "npx vite preview --port 5173 --strictPort" : "npm run dev",
    url: "http://localhost:5173/watch/",
    reuseExistingServer: true,
    timeout: 60_000,
  },
});
