jest.mock('../src/integrations/NativePCRemote', () => ({__esModule: true, default: {createId: () => '2f031c72-3bea-465d-8e61-c67f3fb10dd4', readClipboard: jest.fn(), writeClipboard: jest.fn().mockResolvedValue(undefined), savePreferences: jest.fn(), setForegroundSync: jest.fn()}}));

import NativePCRemote from '../src/integrations/NativePCRemote';
import {RemoteStore} from '../src/communication/remote';

const state = {v: 1, type: 'state', revision: 10, audio: {available: true, percent: 50, muted: false, error: null}, media: {sessions: []}, clipboardRevision: 5, sync: {upload: false, download: false, paused: false}};
const event = (eventId: string, revision: number, text = 'Olá 👋') => ({v: 1, type: 'clipboard.event', eventId, origin: 'windows', revision, text, automatic: true});
const receive = (store: RemoteStore, value: object, allowAuto = true) => (store as unknown as {onMessage: (raw: string, allowAuto: boolean) => void}).onMessage(JSON.stringify(value), allowAuto);
const localCopy = (store: RemoteStore, text: string) => (store as unknown as {onLocalClipboard: (payload: {text: string; sensitive: boolean}) => void}).onLocalClipboard({text, sensitive: false});
const share = (store: RemoteStore, text: string) => (store as unknown as {onShared: (text: string) => void}).onShared(text);

beforeEach(() => jest.clearAllMocks());

test('accepts current revision manual reads, ignores duplicate and older events', () => {
  const store = new RemoteStore();
  receive(store, state);
  const id = 'b991ac70-241b-45d1-8d03-ef5ac2472be0';
  receive(store, {...event(id, 5), automatic: false});
  expect(store.snapshot.receivedText).toBe('Olá 👋');
  receive(store, event(id, 6, 'duplicate'));
  receive(store, event('da98442d-5fd3-49dc-9d80-2b354e2ac522', 4, 'older'));
  expect(store.snapshot.receivedText).toBe('Olá 👋');
});

test('never auto applies a clipboard event buffered during background', () => {
  const store = new RemoteStore();
  receive(store, state);
  store.snapshot = {...store.snapshot, download: true, autoApply: true, foreground: true};
  receive(store, event('4ce108f2-9386-4e03-a1f0-37b441818b2e', 6), false);
  expect(store.snapshot.receivedText).toBe('Olá 👋');
  expect(NativePCRemote.writeClipboard).not.toHaveBeenCalled();
});

test('uses observed clipboard revision for the next automatic copy', async () => {
  const store = new RemoteStore();
  receive(store, state);
  receive(store, event('4ce108f2-9386-4e03-a1f0-37b441818b2e', 6));
  const request = jest.spyOn(store, 'request').mockResolvedValue();
  await store.sendText('Próxima cópia', true);
  expect(request).toHaveBeenCalledWith(expect.objectContaining({kind: 'clipboard.set', baseRevision: 6, automatic: true}));
});

test.each(['automatic', 'manual', 'failed'] as const)('uploads genuine repeated copies after a %s PC clipboard write', async mode => {
  const store = new RemoteStore();
  store.snapshot = {...store.snapshot, upload: true, download: true, autoApply: mode !== 'manual', foreground: true};
  const send = jest.spyOn(store, 'sendText').mockResolvedValue();
  if (mode === 'failed') {jest.mocked(NativePCRemote.writeClipboard).mockRejectedValueOnce(new Error('Unavailable'));}
  receive(store, event('4ce108f2-9386-4e03-a1f0-37b441818b2e', 6, 'A'));
  if (mode === 'manual') {await store.copyReceived();}
  await Promise.resolve();
  // Native filters its own write notification; these are subsequent user copies.
  localCopy(store, 'B');
  localCopy(store, 'A');
  expect(send.mock.calls).toEqual([['B', true], ['A', true]]);
});

test('clears the acknowledged share when no newer share arrived', async () => {
  const store = new RemoteStore();
  share(store, 'A');
  const send = jest.spyOn(store, 'sendText').mockResolvedValue();
  await store.sendClipboard();
  expect(send).toHaveBeenCalledWith('A', false);
  expect(store.snapshot.sharedText).toBe('');
});

test.each(['B', 'A'])('retains a newer share %s while an earlier share upload awaits acknowledgement', async newer => {
  const store = new RemoteStore();
  share(store, 'A');
  let acknowledge!: () => void;
  jest.spyOn(store, 'sendText').mockImplementationOnce(() => new Promise(resolve => {acknowledge = resolve;}));
  const sending = store.sendClipboard();
  share(store, newer);
  acknowledge();
  await sending;
  expect(store.snapshot.sharedText).toBe(newer);
});

test('retains a newer share when an earlier upload fails', async () => {
  const store = new RemoteStore();
  share(store, 'A');
  let fail!: (error: Error) => void;
  jest.spyOn(store, 'sendText').mockImplementationOnce(() => new Promise((_resolve, reject) => {fail = reject;}));
  const sending = store.sendClipboard();
  share(store, 'B');
  fail(new Error('Interrupted'));
  await expect(sending).rejects.toThrow('Interrupted');
  expect(store.snapshot.sharedText).toBe('B');
});

test('retains a share received while reading the local clipboard for an earlier send', async () => {
  const store = new RemoteStore();
  let read!: (text: string) => void;
  jest.mocked(NativePCRemote.readClipboard).mockImplementationOnce(() => new Promise(resolve => {read = resolve;}));
  const send = jest.spyOn(store, 'sendText').mockResolvedValue();
  const sending = store.sendClipboard();
  share(store, 'B');
  read('A');
  await sending;
  expect(send).toHaveBeenCalledWith('A', false);
  expect(store.snapshot.sharedText).toBe('B');
});

test('duplicate state snapshots do not trigger extra renders', () => {
  const store = new RemoteStore();
  const listener = jest.fn();
  store.subscribe(listener);
  receive(store, state);
  receive(store, state);
  expect(listener).toHaveBeenCalledTimes(1);
});

test('throttles dragging, always sends final value, and holds it over stale snapshots', async () => {
  jest.useFakeTimers();
  jest.setSystemTime(1000);
  try {
    const store = new RemoteStore();
    receive(store, state);
    let confirmFinal!: () => void;
    const sent: number[] = [];
    jest.spyOn(store, 'request').mockImplementation(action => {
      if (action.kind === 'volume.set') {
        sent.push(action.percent);
        if (action.percent === 75) {return new Promise(resolve => {confirmFinal = resolve;});}
      }
      if (action.kind === 'state.get') {receive(store, {...state, revision: 12, audio: {...state.audio, percent: 75}});}
      return Promise.resolve();
    });
    store.beginVolume(50);
    store.updateVolume(55);
    store.updateVolume(70);
    expect(sent).toEqual([50]);
    jest.advanceTimersByTime(100);
    expect(sent).toEqual([50, 70]);
    store.endVolume(75);
    receive(store, {...state, revision: 11, audio: {...state.audio, percent: 55}});
    expect(store.snapshot.draggingVolume).toBe(75);
    expect(sent).toEqual([50, 70, 75]);
    confirmFinal();
    await Promise.resolve();
    await Promise.resolve();
    expect(store.snapshot.draggingVolume).toBeNull();
    expect(store.snapshot.state?.audio.percent).toBe(75);
    receive(store, state);
    expect(store.snapshot.state?.audio.percent).toBe(75);
  } finally {jest.useRealTimers();}
});
