import type {Action} from '../domain/protocol';

type Move = Extract<Action, {kind: 'pointer.move'}>;

// One request in flight and one bounded pending delta. Old motion is disposable.
export class InputMotion {
  private pending: Move | null = null;
  private inFlight = false;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private lastSent = 0;
  private generation = 0;

  constructor(private readonly send: (action: Move) => Promise<void>, private readonly active: () => boolean, private readonly failed: (error: Error) => void) {}

  move(dx: number, dy: number) {
    if (!this.active() || !Number.isFinite(dx) || !Number.isFinite(dy)) {return;}
    const nextX = Math.max(-512, Math.min(512, (this.pending?.dx ?? 0) + dx));
    const nextY = Math.max(-512, Math.min(512, (this.pending?.dy ?? 0) + dy));
    this.pending = nextX || nextY ? {kind: 'pointer.move', dx: nextX, dy: nextY} : null;
    this.flush();
  }

  cancel() {
    this.generation++;
    this.pending = null;
    if (this.timer) {clearTimeout(this.timer); this.timer = null;}
  }

  private flush() {
    if (!this.pending || this.inFlight || this.timer || !this.active()) {return;}
    const dx = Math.round(this.pending.dx);
    const dy = Math.round(this.pending.dy);
    if (dx === 0 && dy === 0) {return;}
    const delay = Math.max(0, 34 - (Date.now() - this.lastSent));
    if (delay) {this.timer = setTimeout(() => {this.timer = null; this.flush();}, delay); return;}
    const action: Move = {kind: 'pointer.move', dx, dy};
    const remainderX = this.pending.dx - dx;
    const remainderY = this.pending.dy - dy;
    this.pending = remainderX || remainderY ? {kind: 'pointer.move', dx: remainderX, dy: remainderY} : null;
    this.inFlight = true;
    this.lastSent = Date.now();
    const generation = this.generation;
    this.send(action).then(() => {
      this.inFlight = false;
      this.flush();
    }).catch(error => {
      this.inFlight = false;
      if (generation !== this.generation) {this.flush(); return;}
      this.cancel();
      this.failed(error instanceof Error ? error : new Error('Movimento indisponível.'));
    });
  }
}
