import { expect, test } from "@playwright/test";

// Jellyfin's public demo server (demo.jellyfin.org): signing in, browsing and playing a film through the browser
// player. Needs the internet, so it runs with JELLYFIN_DEMO=1 only.
test.skip(!process.env.JELLYFIN_DEMO, "set JELLYFIN_DEMO=1 to try Jellyfin's demo server");

test("a Jellyfin server: sign in, browse, play", async ({ page }) => {
  page.on("console", (m) => m.type() === "error" && console.log("[console]", m.text()));
  await page.goto("#/servers");
  await page.getByRole("button", { name: "Add Jellyfin" }).click();
  await page.getByLabel("Server address").fill("https://demo.jellyfin.org/stable");
  await page.getByRole("button", { name: "Next" }).click();
  await page.getByLabel("User name").fill("demo");
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible({ timeout: 20000 });
  await page.goto("#/library");
  await page.waitForTimeout(4000);
  const libs = await page.locator(".grid .name").allTextContents();
  console.log("libraries:", libs.slice(0, 8));
  await page.getByRole("link", { name: "Movies" }).first().click();
  await page.waitForTimeout(5000);
  console.log("movies:", (await page.locator(".grid .name").allTextContents()).slice(0, 6));
  const play = page.getByRole("button", { name: "Play here" }).first();
  if (await play.count()) {
    await play.click();
    await expect.poll(() => page.evaluate(() => (window as any).playerLab.player.isFileLoaded), { timeout: 30000 }).toBe(true);
    const s = await page.evaluate(() => (window as any).playerLab.player.snapshot);
    console.log("playing:", s.name, s.video?.codec, s.video?.decoder, s.audioDecoder, s.duration);
    await page.waitForTimeout(3000);
    expect(await page.evaluate(() => (window as any).playerLab.player.position)).toBeGreaterThan(1);
  }
});
