import {z} from 'zod';

export const MAX_TEXT_BYTES = 64 * 1024;
export const MAX_FRAME_BYTES = 512 * 1024;
export const uuid = z.string().uuid();
export const audioSchema = z.strictObject({available: z.boolean(), percent: z.number().int().min(0).max(100), muted: z.boolean(), error: z.string().nullable()});
export const capabilitiesSchema = z.strictObject({play: z.boolean(), pause: z.boolean(), previous: z.boolean(), next: z.boolean(), seek: z.boolean()});
export const sessionSchema = z.strictObject({id: z.string().min(1), app: z.string(), title: z.string(), playing: z.boolean(), capabilities: capabilitiesSchema, positionSeconds: z.number().nullable().optional()});
export const syncSchema = z.strictObject({upload: z.boolean(), download: z.boolean(), paused: z.boolean()});
export const inputStateSchema = z.strictObject({allowed: z.boolean(), enabled: z.boolean()});
export const stateSchema = z.strictObject({v: z.literal(1), type: z.literal('state'), revision: z.number().int().nonnegative(), audio: audioSchema, media: z.strictObject({sessions: z.array(sessionSchema)}), clipboardRevision: z.number().int().nonnegative(), sync: syncSchema, input: inputStateSchema.optional()});
export const clipboardEventSchema = z.strictObject({v: z.literal(1), type: z.literal('clipboard.event'), eventId: uuid, origin: z.literal('windows'), revision: z.number().int().nonnegative(), text: z.string(), automatic: z.boolean()}).refine(v => textIsAllowed(v.text), 'Texto inválido');
export const welcomeSchema = z.strictObject({v: z.literal(1), type: z.literal('welcome'), id: uuid, pcId: uuid, pcName: z.string(), capabilities: z.array(z.enum(['audio', 'media', 'clipboard.text', 'input.control']))});
export const ackSchema = z.strictObject({v: z.literal(1), type: z.literal('ack'), id: uuid});
export const errorSchema = z.strictObject({v: z.literal(1), type: z.literal('error'), id: uuid, code: z.enum(['invalid_message', 'version_mismatch', 'unauthorized', 'pairing_expired', 'pairing_denied', 'rate_limited', 'unavailable', 'conflict', 'sensitive', 'too_large', 'clipboard_busy', 'internal']), message: z.string(), retryable: z.boolean()});
export const pairPendingSchema = z.strictObject({v: z.literal(1), type: z.literal('pair.pending'), id: uuid});
export const pairResultSchema = z.strictObject({v: z.literal(1), type: z.literal('pair.result'), id: uuid, pcId: uuid, pcName: z.string(), token: z.string().regex(/^[a-fA-F0-9]{64}$/)});
export const incomingSchema = z.discriminatedUnion('type', [stateSchema, clipboardEventSchema, welcomeSchema, ackSchema, errorSchema, pairPendingSchema, pairResultSchema]);
export type Incoming = z.infer<typeof incomingSchema>;
export type State = z.infer<typeof stateSchema>;
export type Session = z.infer<typeof sessionSchema>;
export type Action =
  | {kind: 'state.get'}
  | {kind: 'volume.set'; percent: number}
  | {kind: 'volume.step'; delta: -5 | 5}
  | {kind: 'volume.mute'; muted: boolean}
  | {kind: 'media.control'; sessionId: string; command: 'play' | 'pause' | 'previous' | 'next' | 'seek'; offsetSeconds?: -10 | 10}
  | {kind: 'clipboard.set'; eventId: string; origin: 'android'; text: string; automatic: boolean; baseRevision: number}
  | {kind: 'clipboard.get'}
  | {kind: 'sync.set'; upload: boolean; download: boolean; paused: boolean}
  | {kind: 'input.enable'; enabled: boolean}
  | {kind: 'pointer.move'; dx: number; dy: number}
  | {kind: 'pointer.click'; button: 'left' | 'right'; count: 1 | 2}
  | {kind: 'pointer.scroll'; steps: number}
  | {kind: 'keyboard.text'; text: string}
  | {kind: 'keyboard.key'; key: 'enter' | 'backspace' | 'tab' | 'escape' | 'left' | 'right' | 'up' | 'down' | 'delete' | 'home' | 'end'};

export function inputTextIsAllowed(value: string): boolean {
  if (!value || utf8Size(value) > 1024) {return false;}
  for (const character of value) {
    const point = character.codePointAt(0)!;
    if (point < 0x20 || (point >= 0x7f && point <= 0x9f)) {return false;}
  }
  return true;
}

export function textIsAllowed(value: string): boolean {
  return !value.includes('\0') && utf8Size(value) <= MAX_TEXT_BYTES;
}

export function utf8Size(value: string): number {
  let size = 0;
  for (let i = 0; i < value.length; i++) {
    const cp = value.codePointAt(i)!;
    if (cp >= 0xd800 && cp <= 0xdfff) {return Number.POSITIVE_INFINITY;}
    size += cp <= 0x7f ? 1 : cp <= 0x7ff ? 2 : cp <= 0xffff ? 3 : 4;
    if (cp > 0xffff) {i++;}
  }
  return size;
}

export function parseIncoming(raw: string): Incoming | null {
  if (utf8Size(raw) > MAX_FRAME_BYTES) {return null;}
  try {return incomingSchema.parse(JSON.parse(raw));} catch {return null;}
}
