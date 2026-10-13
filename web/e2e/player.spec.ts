import { expect, test, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { fileURLToPath } from "node:url";

// The browser player against the clips e2e/make-media.sh makes: each one opens, plays with its clock moving, seeks
// and slows to 0.95×. Skipped when the clips haven't been made.

const media = (name: string) => fileURLToPath(new URL(`./media/${name}`, import.meta.url));

interface Snapshot {
  name: string;
  loaded: boolean;
  position: number;
  paused: boolean;
  error: string | null;
  buffering: boolean;
  video: { codec: string; decoder: string; width: number; height: number } | null;
  audioDecoder: string | null;
  audioTracks: { id: number; codec: string }[];
  subtitleTracks: { id: number; codec: string; playable: boolean }[];
  subtitleTrack: number | null;
  chapters: { title: string; start: number }[];
}

const snapshot = (page: Page) =>
  page.evaluate(() => (window as unknown as { playerLab: { player: { snapshot: Snapshot } } }).playerLab.player.snapshot);

async function openClip(page: Page, name: string): Promise<Snapshot> {
  await page.goto("#/lab/player");
  await page.locator('input[type="file"]').setInputFiles(media(name));
  await expect
    .poll(async () => {
      const s = await snapshot(page);
      return s.error ?? (s.loaded && s.name === name);
    }, { timeout: 20_000 })
    .toBeTruthy();
  const s = await snapshot(page);
  expect(s.error).toBeNull();
  return s;
}

/** The player's own position now, not the snapshot (which is refreshed every 200 ms). */
const livePosition = (page: Page) => page.evaluate(() => (window as any).playerLab.player.position as number);

async function playFor(page: Page, seconds: number): Promise<[number, number]> {
  if ((await snapshot(page)).paused) await page.getByRole("button", { name: "Play" }).click();
  // Let the sound start (and a speed change settle) before measuring.
  await page.waitForTimeout(500);
  const before = await livePosition(page);
  await page.waitForTimeout(seconds * 1000);
  const after = await livePosition(page);
  return [before, after];
}

const clips: { file: string; video: string; audio: string | null }[] = [
  { file: "hevc-dts-ass.mkv", video: "hvc1", audio: "software" },
  { file: "h264-ac3-aac-chapters.mkv", video: "avc1", audio: "software" },
  { file: "h264-aac.mp4", video: "avc1", audio: "hardware" },
  { file: "mpeg4-mp3.avi", video: "mpeg4", audio: "hardware" },
  { file: "mpeg2-ac3.m2ts", video: "mpeg2video", audio: "software" },
  { file: "webm-vp9-opus.webm", video: "vp09", audio: "hardware" },
];

/** Whether this browser decodes HEVC, which needs hardware support (none on CI's Linux runners). */
async function decodesHevc(page: Page): Promise<boolean> {
  await page.goto("#/lab/player");
  return page.evaluate(async () => {
    if (typeof VideoDecoder === "undefined") return false;
    const support = await VideoDecoder.isConfigSupported({ codec: "hvc1.1.6.L120.90", codedWidth: 1920, codedHeight: 1080 });
    return support.supported === true;
  });
}

for (const clip of clips) {
  test(`${clip.file} opens, plays, seeks and slows down`, async ({ page }) => {
    test.skip(!existsSync(media(clip.file)), "run e2e/make-media.sh first");
    if (clip.video === "hvc1") test.skip(!(await decodesHevc(page)), "this browser has no HEVC decoder");
    page.on("console", (m) => m.type() === "error" && console.log(`[${clip.file}]`, m.text()));
    const opened = await openClip(page, clip.file);
    expect(opened.video?.codec.startsWith(clip.video) || opened.video?.codec === clip.video).toBe(true);
    if (clip.audio) expect(opened.audioDecoder).not.toBeNull();

    const [before, after] = await playFor(page, 3);
    expect(after - before).toBeGreaterThan(2.8);
    expect(after - before).toBeLessThan(3.2);
    expect((await snapshot(page)).error).toBeNull();

    await page.evaluate(() => (window as any).playerLab.player.seek(12));
    await expect.poll(async () => (await snapshot(page)).buffering, { timeout: 10_000 }).toBe(false);
    const landed = (await snapshot(page)).position;
    expect(Math.abs(landed - 12)).toBeLessThan(1);

    await page.evaluate(() => (window as any).playerLab.player.setSpeed(0.95));
    const [slowFrom, slowTo] = await playFor(page, 4);
    const rate = (slowTo - slowFrom) / 4;
    expect(rate).toBeGreaterThan(0.93);
    expect(rate).toBeLessThan(0.97);
    expect((await snapshot(page)).error).toBeNull();
    console.log(`${clip.file}: video ${opened.video?.codec} (${opened.video?.decoder}), audio ${opened.audioDecoder}, 0.95× measured ${rate.toFixed(3)}`);
  });
}

test("the HEVC file's ASS subtitles and the chapters are found", async ({ page }) => {
  test.skip(!existsSync(media("hevc-dts-ass.mkv")), "run e2e/make-media.sh first");
  test.skip(!(await decodesHevc(page)), "this browser has no HEVC decoder");
  const hevc = await openClip(page, "hevc-dts-ass.mkv");
  expect(hevc.subtitleTracks.map((t) => t.codec)).toEqual(["ass"]);
  expect(hevc.subtitleTrack).toBe(hevc.subtitleTracks[0].id);

  const chaptered = await openClip(page, "h264-ac3-aac-chapters.mkv");
  expect(chaptered.chapters.map((c) => c.title)).toEqual(["Prologue", "Opening", "Part A"]);
  expect(chaptered.audioTracks.map((t) => t.codec)).toEqual(["ac3", "aac"]);
});

test("ASS subtitles are drawn over the video, styled, and go away when turned off", async ({ page }) => {
  test.skip(!existsSync(media("hevc-dts-ass.mkv")), "run e2e/make-media.sh first");
  test.skip(!(await decodesHevc(page)), "this browser has no HEVC decoder");
  await openClip(page, "hevc-dts-ass.mkv");
  const stage = page.getByRole("region", { name: "Video" });
  // Paused inside the yellow sign (5–9 s): it's drawn at the top of the picture.
  await page.evaluate(() => (window as any).playerLab.player.seek(6.5));
  await expect.poll(async () => (await snapshot(page)).buffering, { timeout: 10_000 }).toBe(false);
  await page.waitForTimeout(800);
  const withSubs = await stage.screenshot({ path: "test-results/subtitles-on.png" });
  await page.getByLabel("Subtitles").selectOption("");
  await page.waitForTimeout(800);
  const without = await stage.screenshot({ path: "test-results/subtitles-off.png" });
  expect(withSubs.equals(without)).toBe(false);
});

test("the seek bar shows the time and, for a file on this device, a picture of that moment", async ({ page }) => {
  test.skip(!existsSync(media("h264-aac.mp4")), "run e2e/make-media.sh first");
  await page.goto("#/player");
  await page.locator('.empty input[type="file"]').setInputFiles(media("h264-aac.mp4"));
  await expect.poll(() => page.evaluate(() => (window as any).playerLab.player.isFileLoaded), { timeout: 20_000 }).toBe(true);
  const track = page.getByRole("slider", { name: "Position" });
  const box = (await track.boundingBox())!;
  await page.mouse.move(box.x + box.width * 0.5, box.y + box.height / 2);
  await expect(page.locator(".hover-time")).toContainText("00:10");
  await expect(page.locator("canvas.preview.shown")).toBeVisible({ timeout: 10_000 });
  await page.locator(".bottom").screenshot({ path: "test-results/seek-preview.png" });
});
