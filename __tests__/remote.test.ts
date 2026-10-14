jest.mock('../src/integrations/NativePCRemote', () => ({__esModule: true, default: {createId: () => '2f031c72-3bea-465d-8e61-c67f3fb10dd4', writeClipboard: jest.fn().mockResolvedValue(undefined), savePreferences: jest.fn(), setForegroundSync: jest.fn()}}));

import NativePCRemote from '../src/integrations/NativePCRemote';
import {RemoteStore} from '../src/communication/remote';

const state = {v: 1, type: 'state', revision: 10, audio: {available: true, percent: 50, muted: false, error: null}, media: {sessions: []}, clipboardRevision: 5, sync: {upload: false, download: false, paused: false}};
const event = (eventId: string, revision: number, text = 'Olá 👋') => ({v: 1, type: 'clipboard.event', eventId, origin: 'windows', revision, text, automatic: true});
const receive = (store: RemoteStore, value: object, allowAuto = true) => (store as unknown as {onMessage: (raw: string, allowAuto: boolean) => void}).onMessage(JSON.stringify(value), allowAuto);

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
