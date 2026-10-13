import { expect, test, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { fileURLToPath } from "node:url";

// Two browsers in one room on a real YarmiplayServerTV (YSTV_TEST_SERVER=host:port, reached over ws:// from the
// development page): one opens a video for the room, the other finds it isn't on the device and picks its copy,
// then play, pause and seeking follow each other.

const server = process.env.YSTV_TEST_SERVER ?? "";
const clip = fileURLToPath(new URL("./media/h264-aac.mp4", import.meta.url));

const position = (page: Page) => page.evaluate(() => (window as any).playerLab.player.position as number);
const paused = (page: Page) => page.evaluate(() => (window as any).playerLab.player.isPaused as boolean);

async function join(page: Page, name: string, room: string) {
  const [host, port] = server.split(":");
  await page.goto("#/join");
  await page.getByLabel("Server", { exact: true }).fill(host);
  await page.getByLabel("Port").fill(port);
  await page.getByLabel("Your name").fill(name);
  await page.getByLabel("Room", { exact: true }).fill(room);
  const rules = page.getByLabel(/community rules/);
  if (await rules.count()) await rules.check();
  await page.getByRole("button", { name: "Join room" }).click();
  await expect(page.getByRole("heading", { name: room, level: 1 })).toBeVisible({ timeout: 15_000 });
}

test.skip(!server, "set YSTV_TEST_SERVER=host:port");
test.skip(!existsSync(clip), "run e2e/make-media.sh first");

test("two browsers watch together", async ({ browser }) => {
  const room = `web-e2e-${Date.now()}`;
  const ana = await browser.newPage();
  // Ana doesn't share her files, so Bob has to find his own copy.
  await ana.addInitScript(() => localStorage.setItem("ytv:share_files", "false"));
  const bob = await browser.newPage();
  await join(ana, "ana", room);
  await join(bob, "bob", room);

  // Ana opens a video: it becomes the room's selection.
  await ana.goto("#/player");
  await ana.locator('.empty input[type="file"]').setInputFiles(clip);
  await expect.poll(() => ana.evaluate(() => (window as any).playerLab.player.isFileLoaded), { timeout: 20_000 }).toBe(true);

  // Bob is told the room's file isn't on this device, and picks his copy.
  await bob.goto("#/player");
  await expect(bob.getByRole("heading", { name: "Couldn't find this file" })).toBeVisible({ timeout: 15_000 });
  await bob.locator('.sheet input[type="file"]').setInputFiles(clip);
  await expect.poll(() => bob.evaluate(() => (window as any).playerLab.player.isFileLoaded), { timeout: 20_000 }).toBe(true);

  // Both are ready once loaded; Ana presses play and Bob follows.
  await ana.goto("#/room");
  await expect(ana.getByRole("region", { name: "Room" }).getByText("bob", { exact: true })).toBeVisible();
  await ana.goto("#/player");
  await ana.keyboard.press("k");
  await expect.poll(() => paused(bob), { timeout: 10_000 }).toBe(false);
  await bob.waitForTimeout(2000);
  expect(Math.abs((await position(ana)) - (await position(bob)))).toBeLessThan(1);

  // Bob seeks forward ten seconds and pauses; Ana follows both.
  await bob.keyboard.press("l");
  await expect.poll(async () => Math.abs((await position(ana)) - (await position(bob))), { timeout: 10_000 }).toBeLessThan(1);
  await bob.keyboard.press("k");
  await expect.poll(() => paused(ana), { timeout: 10_000 }).toBe(true);
  expect(Math.abs((await position(ana)) - (await position(bob)))).toBeLessThan(1);

  // Chat goes both ways.
  await bob.goto("#/room");
  await bob.getByLabel("Chat message").fill("hi from bob");
  await bob.getByRole("button", { name: "Send" }).click();
  await ana.goto("#/room");
  await expect(ana.getByRole("region", { name: "Chat" }).getByText("hi from bob", { exact: true })).toBeVisible();
});

test("a viewer without the file plays another viewer's copy through the relay", async ({ browser }) => {
  const room = `web-relay-${Date.now()}`;
  const ana = await browser.newPage();
  const bob = await browser.newPage();
  await join(ana, "ana", room);
  await join(bob, "bob", room);
  // The relay needs a session on both sides.
  for (const page of [ana, bob]) {
    await expect.poll(() => page.evaluate(() => (window as any).ytv?.session?.value?.syncClient?.session != null), { timeout: 10_000 }).toBe(true);
  }

  await ana.goto("#/player");
  await ana.locator('.empty input[type="file"]').setInputFiles(clip);
  await expect.poll(() => ana.evaluate(() => (window as any).playerLab.player.isFileLoaded), { timeout: 20_000 }).toBe(true);

  // Bob has nothing: his browser plays Ana's copy through the server, without asking.
  await bob.goto("#/player");
  await expect.poll(() => bob.evaluate(() => (window as any).playerLab.player.isFileLoaded), { timeout: 30_000 }).toBe(true);
  await expect(bob.getByRole("heading", { name: "Couldn't find this file" })).toHaveCount(0);

  await ana.keyboard.press("k");
  await expect.poll(() => paused(bob), { timeout: 10_000 }).toBe(false);
  await bob.waitForTimeout(3000);
  expect(Math.abs((await position(ana)) - (await position(bob)))).toBeLessThan(1);
  expect(await position(bob)).toBeGreaterThan(2);
});
