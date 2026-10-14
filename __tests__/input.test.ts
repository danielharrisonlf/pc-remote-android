import {InputMotion} from '../src/communication/InputMotion';
import {inputTextIsAllowed, parseIncoming} from '../src/domain/protocol';
import {RemoteStore} from '../src/communication/remote';

jest.mock('../src/integrations/NativePCRemote', () => ({__esModule: true, default: {createId: () => '2f031c72-3bea-465d-8e61-c67f3fb10dd4'}}));

const id = '54e9c4cb-272d-48dd-8772-bba7103e72e6';
const state = {v: 1, type: 'state', revision: 1, audio: {available: true, percent: 50, muted: false, error: null}, media: {sessions: []}, clipboardRevision: 0, sync: {upload: false, download: false, paused: false}};
const receive = (store: RemoteStore, value: object) => (store as unknown as {onMessage: (raw: string) => void}).onMessage(JSON.stringify(value));

test('accepts legacy state but requires explicit input capability and state', () => {
  expect(parseIncoming(JSON.stringify(state))).toEqual(state);
  expect(parseIncoming(JSON.stringify({...state, input: {allowed: true, enabled: false}}))).toEqual({...state, input: {allowed: true, enabled: false}});
  expect(parseIncoming(JSON.stringify({...state, input: {allowed: 'yes', enabled: true}}))).toBeNull();
  const welcome = {v: 1, type: 'welcome', id, pcId: id, pcName: 'PC', capabilities: ['audio', 'input.control']};
  expect(parseIncoming(JSON.stringify(welcome))).toEqual(welcome);
});

test('validates Unicode by UTF-8 bytes and excludes control characters', () => {
  expect(inputTextIsAllowed('á😀漢字')).toBe(true);
  expect(inputTextIsAllowed('😀'.repeat(256))).toBe(true);
  expect(inputTextIsAllowed('😀'.repeat(257))).toBe(false);
  expect(inputTextIsAllowed('')).toBe(false);
  expect(inputTextIsAllowed('a\nb')).toBe(false);
  expect(inputTextIsAllowed('a\tb')).toBe(false);
  expect(inputTextIsAllowed('a\0b')).toBe(false);
  expect(inputTextIsAllowed('\ud800')).toBe(false);
});

test('sends at most one movement in flight and drops queued movement on cancel', async () => {
  jest.useFakeTimers();
  jest.setSystemTime(1000);
  try {
    let resolve!: () => void;
    const send = jest.fn().mockImplementation(() => new Promise<void>(r => {resolve = r;}));
    let active = true;
    const motion = new InputMotion(send, () => active, jest.fn());
    motion.move(2, 3);
    motion.move(4, 5);
    motion.move(6, 7);
    expect(send).toHaveBeenCalledTimes(1);
    resolve();
    await Promise.resolve();
    jest.advanceTimersByTime(34);
    expect(send).toHaveBeenCalledTimes(2);
    expect(send).toHaveBeenLastCalledWith({kind: 'pointer.move', dx: 10, dy: 12});
    motion.move(8, 9);
    motion.cancel();
    motion.move(10, 11);
    expect(send).toHaveBeenCalledTimes(2);
    resolve();
    await Promise.resolve();
    jest.advanceTimersByTime(34);
    expect(send).toHaveBeenCalledTimes(3);
    expect(send).toHaveBeenLastCalledWith({kind: 'pointer.move', dx: 10, dy: 11});
    active = false;
    motion.cancel();
  } finally {jest.useRealTimers();}
});

test('preserves fractional motion until it forms a native pixel', async () => {
  const send = jest.fn().mockResolvedValue(undefined);
  const motion = new InputMotion(send, () => true, jest.fn());
  motion.move(0.2, 0);
  motion.move(0.2, 0);
  expect(send).not.toHaveBeenCalled();
  motion.move(0.2, 0);
  expect(send).toHaveBeenCalledWith({kind: 'pointer.move', dx: 1, dy: 0});
  motion.cancel();
  await Promise.resolve();
  expect(send).toHaveBeenCalledTimes(1);
});

test('disables on disconnect and refuses stale taps', async () => {
  const store = new RemoteStore();
  store.snapshot = {...store.snapshot, status: 'conectado', inputFeature: true, inputArmed: true, state: {...state, input: {allowed: true, enabled: true}} as never};
  const request = jest.spyOn(store, 'request').mockResolvedValue();
  await store.inputAction({kind: 'pointer.click', button: 'left', count: 1});
  expect(request).toHaveBeenCalledWith({kind: 'pointer.click', button: 'left', count: 1});
  receive(store, {...state, revision: 2, input: {allowed: false, enabled: false}});
  expect(store.snapshot.inputArmed).toBe(false);
  await expect(store.inputAction({kind: 'keyboard.key', key: 'enter'})).rejects.toThrow();
});

test('background disarm sends disable while activation is pending and ignores its late ACK', async () => {
  const store = new RemoteStore();
  store.snapshot = {...store.snapshot, status: 'conectado', inputFeature: true, state: {...state, input: {allowed: true, enabled: false}} as never};
  let ackEnable!: () => void;
  const request = jest.spyOn(store, 'request').mockImplementation(action => action.kind === 'input.enable' && action.enabled
    ? new Promise<void>(resolve => {ackEnable = resolve;}) : Promise.resolve());
  const activating = store.enableInput();
  expect(store.snapshot.inputActivating).toBe(true);
  store.disableInput();
  store.snapshot = {...store.snapshot, foreground: false};
  expect(request).toHaveBeenCalledWith({kind: 'input.enable', enabled: false});
  ackEnable();
  await activating;
  expect(store.snapshot.inputArmed).toBe(false);
  expect(store.snapshot.inputActivating).toBe(false);
});

test('a queued disabled snapshot cannot cancel fresh activation confirmed by a newer state', async () => {
  const store = new RemoteStore();
  store.snapshot = {...store.snapshot, status: 'conectado', inputFeature: true, state: {...state, input: {allowed: true, enabled: false}} as never};
  let ackEnable!: () => void;
  jest.spyOn(store, 'request').mockImplementation(action => action.kind === 'input.enable'
    ? new Promise<void>(resolve => {ackEnable = resolve;}) : Promise.resolve());
  const activating = store.enableInput();
  receive(store, {...state, revision: 2, input: {allowed: true, enabled: false}});
  receive(store, {...state, revision: 3, input: {allowed: true, enabled: true}});
  ackEnable();
  await activating;
  expect(store.snapshot.inputArmed).toBe(true);
});

test('PC revocation during activation prevents a late ACK from arming controls', async () => {
  const store = new RemoteStore();
  store.snapshot = {...store.snapshot, status: 'conectado', inputFeature: true, state: {...state, input: {allowed: true, enabled: false}} as never};
  let ackEnable!: () => void;
  jest.spyOn(store, 'request').mockImplementation(action => action.kind === 'input.enable'
    ? new Promise<void>(resolve => {ackEnable = resolve;}) : Promise.resolve());
  const activating = store.enableInput();
  receive(store, {...state, revision: 2, input: {allowed: false, enabled: false}});
  ackEnable();
  await activating;
  expect(store.snapshot.inputArmed).toBe(false);
  expect(store.snapshot.inputActivating).toBe(false);
});
