import { Constants, type Clock } from "./models";

/** Port of Syncplay's PingService: round-trip time and one-way delay estimation. */
export class PingService {
  private rtt = 0;
  private forwardDelay = 0;
  private avgRtt = 0;

  constructor(private readonly clock: Clock) {}

  newTimestamp(): number {
    return this.clock();
  }

  receiveMessage(timestamp: number | null | undefined, senderRtt: number): void {
    if (timestamp == null || timestamp === 0) return;
    this.rtt = this.clock() - timestamp;
    if (this.rtt < 0 || senderRtt < 0) return;
    if (this.avgRtt === 0) this.avgRtt = this.rtt;
    const w = Constants.PING_MOVING_AVERAGE_WEIGHT;
    this.avgRtt = this.avgRtt * w + this.rtt * (1 - w);
    this.forwardDelay = senderRtt < this.rtt ? this.avgRtt / 2 + (this.rtt - senderRtt) : this.avgRtt / 2;
  }

  getRtt(): number {
    return this.rtt;
  }

  getLastForwardDelay(): number {
    return this.forwardDelay;
  }
}
