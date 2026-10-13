import { SeekFrames } from "../player/seekFrames";
import type { LocalFile } from "./library";

/**
 * Thumbnails for the videos on this device (VideoThumbnails.kt): a picture from a tenth of the way in, made one at a
 * time (each opens its own FFmpeg) and kept as object URLs until the page closes.
 */
const made = new Map<string, Promise<string | null>>();
let queue: Promise<unknown> = Promise.resolve();

const keyOf = (f: LocalFile) => `${f.folder}/${f.directory}/${f.name}|${f.size}|${f.modified}`;

export function thumbnail(file: LocalFile, width = 240): Promise<string | null> {
  const key = keyOf(file);
  let job = made.get(key);
  if (!job) {
    job = queue.then(async () => {
      const frames = await SeekFrames.open(await file.open()).catch(() => null);
      if (!frames) return null;
      try {
        // The demuxer doesn't say the length here; 60 s in (or the start of a short file) shows the picture.
        const bitmap = (await frames.frame(60, width)) ?? (await frames.frame(0, width));
        if (!bitmap) return null;
        const canvas = new OffscreenCanvas(bitmap.width, bitmap.height);
        canvas.getContext("2d")!.drawImage(bitmap, 0, 0);
        bitmap.close();
        return URL.createObjectURL(await canvas.convertToBlob({ type: "image/jpeg", quality: 0.8 }));
      } finally {
        await frames.close();
      }
    });
    queue = job.catch(() => null);
    made.set(key, job);
  }
  return job;
}
