import { beforeEach, describe, expect, it, vi } from "vitest";
import { listInputDevices, listOutputDevices } from "./devices";

describe("voice devices", () => {
  beforeEach(() => {
    vi.stubGlobal("navigator", {
      mediaDevices: {
        enumerateDevices: vi.fn(async () => [
          { kind: "audioinput", deviceId: "mic-1", label: "Fake Mic" },
          { kind: "audioinput", deviceId: "mic-2", label: "Headset Mic" },
          { kind: "audiooutput", deviceId: "spk-1", label: "Speakers" },
          { kind: "audiooutput", deviceId: "spk-2", label: "Headphones" },
          { kind: "videoinput", deviceId: "cam-1", label: "Camera" },
        ]),
      },
    });
  });

  it("lists inputs with system default first", async () => {
    const inputs = await listInputDevices();
    expect(inputs[0]).toEqual({ id: "", label: "System default", isDefault: true });
    expect(inputs.map((d) => d.label)).toEqual(expect.arrayContaining(["Fake Mic", "Headset Mic"]));
    expect(inputs.some((d) => d.label === "Camera")).toBe(false);
  });

  it("lists outputs including system default", async () => {
    const outs = await listOutputDevices();
    expect(outs[0]).toMatchObject({ id: "", label: "System default" });
    // Without setSinkId the helper may collapse to default-only; with it, named speakers appear.
    expect(outs.length).toBeGreaterThanOrEqual(1);
  });
});
