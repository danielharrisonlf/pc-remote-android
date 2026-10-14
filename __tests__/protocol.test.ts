import {parseIncoming, textIsAllowed} from '../src/domain/protocol';
import examples from '../docs/protocol-v1.examples.json';

const id = '54e9c4cb-272d-48dd-8772-bba7103e72e6';

test('preserves Unicode and line breaks in clipboard events', () => {
  const text = 'Olá 👋\nhttps://example.org';
  expect(parseIncoming(JSON.stringify({v: 1, type: 'clipboard.event', eventId: id, origin: 'windows', revision: 2, text, automatic: true}))).toMatchObject({text});
});

test('rejects malformed and oversized clipboard content', () => {
  expect(textIsAllowed('x'.repeat(65_536))).toBe(true);
  expect(textIsAllowed('😀'.repeat(16_385))).toBe(false);
  expect(textIsAllowed('a\0b')).toBe(false);
  expect(parseIncoming(JSON.stringify({v: 1, type: 'clipboard.event', eventId: id, origin: 'windows', revision: 2, text: 'a\0b', automatic: true}))).toBeNull();
  expect(parseIncoming(JSON.stringify({v: 1, type: 'clipboard.event', eventId: id, origin: 'windows', revision: 2, text: '"'.repeat(65_536), automatic: true}))).not.toBeNull();
  expect(parseIncoming(JSON.stringify({v: 1, type: 'clipboard.event', eventId: id, origin: 'windows', revision: 2, text: '\u0001'.repeat(65_536), automatic: true}))).not.toBeNull();
});

test('requires exact protocol version and known schema', () => {
  const base = {v: 1, type: 'ack', id};
  expect(parseIncoming(JSON.stringify(base))).toMatchObject(base);
  expect(parseIncoming(JSON.stringify({...base, v: 2}))).toBeNull();
  expect(parseIncoming(JSON.stringify({...base, extra: 'command'}))).toBeNull();
  expect(parseIncoming('{')).toBeNull();
});

test('validates capabilities from real session state', () => {
  const message = {v: 1, type: 'state', revision: 4, audio: {available: true, percent: 37, muted: false, error: null}, media: {sessions: [{id: 'player', app: 'Browser', title: 'Vídeo', playing: true, capabilities: {play: true, pause: true, previous: false, next: false, seek: true}, positionSeconds: 20}]}, clipboardRevision: 0, sync: {upload: false, download: false, paused: false}};
  expect(parseIncoming(JSON.stringify(message))).toEqual(message);
  expect(parseIncoming(JSON.stringify({...message, audio: {...message.audio, percent: 101}}))).toBeNull();
});

test('parses every frozen server fixture shared with Windows', () => {
  for (const message of examples.server) {
    expect(parseIncoming(JSON.stringify(message))).toEqual(message);
  }
});
