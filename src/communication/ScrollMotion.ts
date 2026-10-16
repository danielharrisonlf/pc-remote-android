/** Protocol limit for one `pointer.scroll` request (src-tauri/src/protocol.rs). */
export const MAX_SCROLL_STEPS = 10;
/** Pending steps beyond this are dropped: a long fling should not scroll forever. */
const MAX_PENDING_STEPS = 30;

const clamp = (value: number, limit: number) => Math.max(-limit, Math.min(limit, value));

/**
 * Batches wheel steps from drag gestures. One request is in flight at a time and
 * steps that arrive meanwhile are merged into the next request, like InputMotion
 * does for pointer movement.
 */
export class ScrollMotion {
  private pending = 0;
  private inFlight = false;
  private generation = 0;

  constructor(
    private readonly send: (steps: number) => Promise<void>,
    private readonly active: () => boolean,
    private readonly failed: (error: Error) => void,
  ) {}

  add(steps: number) {
    if (!this.active() || !Number.isFinite(steps)) {
      return;
    }
    const whole = Math.trunc(steps);
    if (!whole) {
      return;
    }
    this.pending = clamp(this.pending + whole, MAX_PENDING_STEPS);
    this.flush();
  }

  cancel() {
    this.generation++;
    this.pending = 0;
  }

  private flush() {
    if (this.inFlight || this.pending === 0 || !this.active()) {
      return;
    }
    const steps = clamp(this.pending, MAX_SCROLL_STEPS);
    this.pending -= steps;
    this.inFlight = true;
    const generation = this.generation;
    this.send(steps)
      .then(() => {
        this.inFlight = false;
        this.flush();
      })
      .catch(error => {
        this.inFlight = false;
        if (generation !== this.generation) {
          this.flush();
          return;
        }
        this.cancel();
        this.failed(error instanceof Error ? error : new Error('Rolagem indisponível.'));
      });
  }
}
