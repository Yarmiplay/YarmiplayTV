import { expect, it } from "vitest";
import * as Media from "./index";

it("the module's entry point exports the clients, sources, locator and reporter", () => {
  expect(typeof Media.JellyfinClient).toBe("function");
  expect(typeof Media.JellyfinSource).toBe("function");
  expect(typeof Media.PlexClient).toBe("function");
  expect(typeof Media.PlexSource).toBe("function");
  expect(typeof Media.CompositeMediaSource).toBe("function");
  expect(typeof Media.MediaLocator).toBe("function");
  expect(typeof Media.PlaybackReporter).toBe("function");
  expect(typeof Media.FileNames.parse).toBe("function");
  expect(typeof Media.serverNowPlaying).toBe("function");
  expect(typeof Media.loadSessions).toBe("function");
});
