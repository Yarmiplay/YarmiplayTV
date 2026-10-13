// The parts of libavjs-webcodecs-bridge the player uses. The package ships its TypeScript sources as types, which need
// two more packages' types to check; tsconfig.json points the import here instead (the bundler still loads the package).
import type { LavFrame, LavPacket, LavStream, LibAV } from "../lib/player/libav";

type TimeBase = { time_base_num: number; time_base_den: number } | [number, number];

export function videoStreamToConfig(libav: LibAV, stream: LavStream): Promise<VideoDecoderConfig | null>;
export function audioStreamToConfig(libav: LibAV, stream: LavStream): Promise<AudioDecoderConfig | null>;
export function packetToEncodedVideoChunk(packet: LavPacket, timeBase: TimeBase): EncodedVideoChunk;
export function packetToEncodedAudioChunk(packet: LavPacket, timeBase: TimeBase): EncodedAudioChunk;
export function laFrameToVideoFrame(frame: LavFrame, opts?: { timeBase?: [number, number]; transfer?: boolean }): VideoFrame;
export function laFrameToAudioData(frame: LavFrame, opts?: { timeBase?: [number, number] }): AudioData;
