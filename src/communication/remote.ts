import {AppState, NativeEventEmitter, Platform} from 'react-native';
import NativePCRemote from '../integrations/NativePCRemote';
import {deviceDisplayName} from '../domain/device';
import {inputTextIsAllowed, parseIncoming, textIsAllowed, type Action, type Session, type State} from '../domain/protocol';
import {InputMotion} from './InputMotion';
import {ScrollMotion} from './ScrollMotion';

export type ThemePreference = 'system' | 'light' | 'dark';
const themes: ThemePreference[] = ['system', 'light', 'dark'];

export type RemoteView = {
  /** True once the saved computer and preferences were read from native storage. */
  ready: boolean;
  theme: ThemePreference;
  /** Release version the user chose to ignore in the update banner. */
  skippedUpdate: string;
  status: 'desconectado' | 'conectando' | 'pareando' | 'aguardando aprovação' | 'conectado' | 'reconectando';
  pcName: string;
  error: string;
  state: State | null;
  selectedSessionId: string;
  receivedText: string;
  sharedText: string;
  upload: boolean;
  download: boolean;
  paused: boolean;
  autoApply: boolean;
  haptics: boolean;
  foreground: boolean;
  draggingVolume: number | null;
  inputFeature: boolean;
  inputArmed: boolean;
  inputActivating: boolean;
};

type Listener = () => void;
export class RemoteStore {
  private listeners = new Set<Listener>();
  private eventIds = new Set<string>();
  private pending = new Map<string, {resolve: () => void; reject: (reason: Error) => void; timer: ReturnType<typeof setTimeout>}>();
  private generation = 0;
  private lastVolumeAt = 0;
  private delayedVolume: ReturnType<typeof setTimeout> | null = null;
  private sharedTextGeneration = 0;
  private lastClipboardEventRevision = -1;
  private observedClipboardRevision = 0;
  private dragGeneration = 0;
  private finalVolume: {value: number; gen: number; drag: number; baseRevision: number; acked: boolean} | null = null;
  private alive = false;
  private inputGeneration = 0;
  private inputDiscretePending = false;
  snapshot: RemoteView = {ready: false, theme: 'system', skippedUpdate: '', status: 'desconectado', pcName: '', error: '', state: null, selectedSessionId: '', receivedText: '', sharedText: '', upload: false, download: false, paused: false, autoApply: false, haptics: true, foreground: true, draggingVolume: null, inputFeature: false, inputArmed: false, inputActivating: false};
  private motion = new InputMotion(action => this.request(action), () => this.canSendInput(), error => this.patch({error: error.message}));
  private scrollMotion = new ScrollMotion(steps => this.request({kind: 'pointer.scroll', steps}), () => this.canSendInput(), error => this.patch({error: error.message}));

  subscribe = (listener: Listener) => {this.listeners.add(listener); return () => {this.listeners.delete(listener);};};
  getSnapshot = () => this.snapshot;
  private patch(change: Partial<RemoteView>) {this.snapshot = {...this.snapshot, ...change}; this.listeners.forEach(l => l());}

  start() {
    if (this.alive) {return;}
    this.alive = true;
    const emitter = new NativeEventEmitter(NativePCRemote);
    emitter.addListener('remote.message', raw => this.onMessage(raw as string));
    emitter.addListener('remote.backgroundClipboard', raw => this.onMessage(raw as string, false));
    emitter.addListener('remote.status', status => this.onStatus(status as string));
    emitter.addListener('remote.clipboard', payload => this.onLocalClipboard(payload as {text: string; sensitive: boolean}));
    emitter.addListener('remote.share', value => this.onShared(value as string));
    emitter.addListener('remote.feedback', value => this.patch({error: value as string}));
    AppState.addEventListener('change', next => {
      const foreground = next === 'active';
      if (!foreground) {this.disableInput();}
      this.patch({foreground});
      NativePCRemote.setForegroundSync(foreground && this.snapshot.upload && !this.snapshot.paused);
      if (foreground) {this.dropInput(); NativePCRemote.acknowledgeInputResume(); NativePCRemote.reconnect();}
    });
    NativePCRemote.getPreferences().then(raw => {
      const value = JSON.parse(raw) as Partial<Pick<RemoteView, 'upload' | 'download' | 'paused' | 'autoApply' | 'haptics' | 'theme' | 'skippedUpdate'>>;
      this.patch({
        upload: !!value.upload, download: !!value.download, paused: !!value.paused, autoApply: !!value.autoApply, haptics: value.haptics !== false,
        theme: themes.includes(value.theme as ThemePreference) ? value.theme! : 'system',
        skippedUpdate: typeof value.skippedUpdate === 'string' ? value.skippedUpdate : '',
      });
      NativePCRemote.setForegroundSync(this.snapshot.foreground && this.snapshot.upload && !this.snapshot.paused);
    }).catch(() => undefined).finally(() => NativePCRemote.getSavedComputer().then(raw => {
      if (raw) {const saved = JSON.parse(raw) as {pcName?: string}; this.patch({pcName: saved.pcName ?? ''}); NativePCRemote.reconnect();}
    }).catch(() => undefined).finally(() => this.patch({ready: true})));
    NativePCRemote.getSharedText().then(text => {if (text) {this.onShared(text);}}).catch(() => undefined);
    NativePCRemote.getPendingRemoteMessage().then(text => {if (text) {this.onMessage(text, false);}}).catch(() => undefined);
    NativePCRemote.getPendingFeedback().then(text => {if (text) {this.patch({error: text});}}).catch(() => undefined);
    NativePCRemote.acknowledgeInputResume();
  }

  private onStatus(status: string) {
    if (status === 'connected') {this.patch({status: 'conectado', error: ''}); return;}
    if (status === 'pending') {this.patch({status: 'aguardando aprovação'}); return;}
    if (status === 'pairing') {this.patch({status: 'pareando'}); return;}
    if (status === 'connecting' || status === 'reconnecting') {this.patch({status: status === 'connecting' ? 'conectando' : 'reconectando'}); return;}
    if (status.startsWith('error:')) {this.dropInput(); this.patch({status: 'desconectado', error: status.slice(6)}); this.rejectPending(); return;}
    if (status === 'disconnected') {this.dropInput(); this.patch({status: 'desconectado', state: null, receivedText: '', draggingVolume: null}); this.rejectPending();}
  }

  private rejectPending() {for (const p of this.pending.values()) {clearTimeout(p.timer); p.reject(new Error('Conexão interrompida.'));} this.pending.clear(); if (this.delayedVolume) {clearTimeout(this.delayedVolume); this.delayedVolume = null;} this.finalVolume = null; this.dragGeneration++; this.generation++;}

  private onMessage(raw: string, allowAutoApply = true) {
    const msg = parseIncoming(raw);
    if (!msg) {this.patch({error: 'Resposta inválida do computador.'}); return;}
    if (msg.type === 'welcome') {this.dropInput(); this.generation++; this.eventIds.clear(); this.lastClipboardEventRevision = -1; this.observedClipboardRevision = 0; this.finalVolume = null; this.patch({status: 'conectado', pcName: msg.pcName, state: null, receivedText: '', draggingVolume: null, inputFeature: msg.capabilities.includes('input.control')}); this.request({kind: 'state.get'}).catch(() => undefined); this.sendSync(); return;}
    if (msg.type === 'state') {
      const prev = this.snapshot.state;
      if (prev && msg.revision < prev.revision) {return;}
      if (prev && msg.revision === prev.revision && !this.finalVolume?.acked) {return;}
      const sessionId = msg.media.sessions.some(s => s.id === this.snapshot.selectedSessionId) ? this.snapshot.selectedSessionId : (msg.media.sessions[0]?.id ?? '');
      this.observedClipboardRevision = Math.max(this.observedClipboardRevision, msg.clipboardRevision);
      // A queued disabled snapshot from the previous lifecycle can arrive while
      // a fresh enable request is pending. It cannot cancel the user's new intent;
      // permission revocation and disabling an already armed session still do.
      if (msg.input && (!msg.input.allowed || (!msg.input.enabled && this.snapshot.inputArmed))) {this.dropInput();}
      const final = this.finalVolume;
      if (final && final.acked && final.gen === this.generation && final.drag === this.dragGeneration && msg.audio.percent === final.value) {
        this.finalVolume = null;
        this.patch({state: msg, selectedSessionId: sessionId, draggingVolume: null});
      } else {this.patch({state: msg, selectedSessionId: sessionId});}
      return;
    }
    if (msg.type === 'clipboard.event') {
      if (this.eventIds.has(msg.eventId) || msg.revision < this.lastClipboardEventRevision || (msg.automatic && this.snapshot.state && msg.revision < this.snapshot.state.clipboardRevision)) {return;}
      this.eventIds.add(msg.eventId);
      this.lastClipboardEventRevision = Math.max(this.lastClipboardEventRevision, msg.revision);
      this.observedClipboardRevision = Math.max(this.observedClipboardRevision, msg.revision);
      if (this.eventIds.size > 256) {this.eventIds.delete(this.eventIds.values().next().value!);}
      this.patch({receivedText: msg.text});
      if (allowAutoApply && this.snapshot.foreground && this.snapshot.download && this.snapshot.autoApply && !this.snapshot.paused) {
        NativePCRemote.writeClipboard(msg.text).catch(() => this.patch({error: 'O Android não permitiu copiar o texto. Use a ação manual com o app em foco.'}));
      }
      return;
    }
    if (msg.type === 'ack' || msg.type === 'error') {
      const p = this.pending.get(msg.id);
      if (!p) {return;}
      clearTimeout(p.timer);
      this.pending.delete(msg.id);
      if (msg.type === 'ack') {p.resolve();} else {p.reject(new Error(msg.message)); this.patch({error: msg.message});}
    }
  }

  private onLocalClipboard(payload: {text: string; sensitive: boolean}) {
    if (!this.snapshot.foreground || !this.snapshot.upload || this.snapshot.paused || payload.sensitive || !textIsAllowed(payload.text)) {return;}
    // Native suppresses notifications caused by its own clipboard writes.
    this.sendText(payload.text, true).catch(() => undefined);
  }
  private onShared(text: string) {
    if (!textIsAllowed(text)) {this.patch({error: 'Texto acima de 64 KiB ou inválido.'}); return;}
    this.sharedTextGeneration++;
    this.patch({sharedText: text});
  }

  connect(host: string, port: number, fingerprint: string, code: string) {
    this.dropInput();
    this.rejectPending();
    this.patch({status: 'pareando', error: '', state: null, receivedText: '', draggingVolume: null});
    // The PC shows this name in its approval prompt, so send the real model.
    const name = Platform.OS === 'android' ? deviceDisplayName(Platform.constants as {Brand?: string; Manufacturer?: string; Model?: string}) : 'Celular Android';
    NativePCRemote.connect(host.trim(), port, fingerprint.trim().toLowerCase().replaceAll(':', ''), code.trim(), name);
  }
  disconnect() {this.dropInput(); NativePCRemote.disconnect(); this.rejectPending(); this.patch({status: 'desconectado', state: null, receivedText: '', draggingVolume: null});}
  forget() {this.disconnect(); NativePCRemote.forgetComputer(); this.patch({pcName: '', receivedText: ''});}
  selectSession(id: string) {this.patch({selectedSessionId: id});}
  get selectedSession(): Session | undefined {return this.snapshot.state?.media.sessions.find(s => s.id === this.snapshot.selectedSessionId);}

  request(action: Action): Promise<void> {
    if (this.snapshot.status !== 'conectado') {return Promise.reject(new Error('Computador desconectado.'));}
    const id = NativePCRemote.createId();
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {this.pending.delete(id); reject(new Error('O PC não confirmou a ação a tempo.'));}, 8000);
      this.pending.set(id, {resolve, reject, timer});
      NativePCRemote.sendRequest(id, JSON.stringify(action));
    });
  }
  async sendText(text: string, automatic: boolean) {
    if (!textIsAllowed(text)) {throw new Error('Texto acima de 64 KiB ou inválido.');}
    await this.request({kind: 'clipboard.set', eventId: NativePCRemote.createId(), origin: 'android', text, automatic, baseRevision: this.observedClipboardRevision});
  }
  async sendClipboard() {
    const sharedText = this.snapshot.sharedText;
    const generation = this.sharedTextGeneration;
    const text = sharedText || await NativePCRemote.readClipboard();
    await this.sendText(text, false);
    if (sharedText && generation === this.sharedTextGeneration) {this.patch({sharedText: ''});}
  }
  async getClipboard() {await this.request({kind: 'clipboard.get'});}
  async copyReceived() {if (this.snapshot.receivedText) {await NativePCRemote.writeClipboard(this.snapshot.receivedText);}}
  clearError() {if (this.snapshot.error) {this.patch({error: ''});}}
  /** Forgets the received text on this phone only; the PC clipboard is untouched. */
  clearReceived() {this.patch({receivedText: ''});}
  /** Round trip of a state read, used as the latency estimate. */
  async ping(): Promise<number> {
    const started = Date.now();
    await this.request({kind: 'state.get'});
    return Date.now() - started;
  }
  setSync(change: Partial<Pick<RemoteView, 'upload' | 'download' | 'paused' | 'autoApply'>>) {
    this.patch(change);
    this.persistPreferences();
    NativePCRemote.setForegroundSync(this.snapshot.foreground && this.snapshot.upload && !this.snapshot.paused);
    this.sendSync();
  }
  setHaptics(enabled: boolean) {this.patch({haptics: enabled}); this.persistPreferences();}
  setTheme(theme: ThemePreference) {this.patch({theme}); this.persistPreferences();}
  skipUpdate(version: string) {this.patch({skippedUpdate: version}); this.persistPreferences();}
  private persistPreferences() {const {upload, download, paused, autoApply, haptics, theme, skippedUpdate} = this.snapshot; NativePCRemote.savePreferences(JSON.stringify({upload, download, paused, autoApply, haptics, theme, skippedUpdate}));}
  private sendSync() {if (this.snapshot.status === 'conectado') {const {upload, download, paused} = this.snapshot; this.request({kind: 'sync.set', upload, download, paused}).catch(() => undefined);}}

  private canSendInput() {
    const {status, foreground, inputFeature, inputArmed, state} = this.snapshot;
    return status === 'conectado' && foreground && inputFeature && inputArmed && state?.input?.allowed === true && state.input.enabled === true;
  }
  private dropInput() {this.inputGeneration++; this.inputDiscretePending = false; this.motion.cancel(); this.scrollMotion.cancel(); if (this.snapshot.inputArmed || this.snapshot.inputActivating) {this.patch({inputArmed: false, inputActivating: false});}}
  disableInput() {
    const wasArmed = this.snapshot.inputArmed || this.snapshot.inputActivating;
    this.dropInput();
    if (wasArmed && this.snapshot.status === 'conectado') {this.request({kind: 'input.enable', enabled: false}).catch(() => undefined);}
  }
  async enableInput() {
    if (this.snapshot.status !== 'conectado' || !this.snapshot.foreground || !this.snapshot.inputFeature || this.snapshot.state?.input?.allowed !== true) {throw new Error('Controle de entrada indisponível neste PC.');}
    if (this.snapshot.inputActivating) {return;}
    const generation = this.inputGeneration;
    this.patch({inputActivating: true});
    try {
      await this.request({kind: 'input.enable', enabled: true});
      if (generation !== this.inputGeneration || this.snapshot.status !== 'conectado' || !this.snapshot.foreground || this.snapshot.state?.input?.enabled !== true) {return;}
      this.patch({inputArmed: true});
      await this.request({kind: 'state.get'});
    } finally {if (generation === this.inputGeneration) {this.patch({inputActivating: false});}}
  }
  movePointer(dx: number, dy: number) {this.motion.move(dx, dy);}
  cancelPointerMotion() {this.motion.cancel();}
  /** Wheel steps from drag gestures; positive scrolls up, like the protocol. */
  scroll(steps: number) {this.scrollMotion.add(steps);}
  cancelScroll() {this.scrollMotion.cancel();}
  get inputReady() {return this.canSendInput();}
  async inputAction(action: Extract<Action, {kind: 'pointer.click' | 'pointer.scroll' | 'keyboard.key' | 'keyboard.text'}>) {
    if (!this.canSendInput()) {throw new Error('Ative o controle de entrada no PC e no celular.');}
    if (this.inputDiscretePending) {throw new Error('Aguarde a confirmação do controle anterior.');}
    if (action.kind === 'keyboard.text' && !inputTextIsAllowed(action.text)) {throw new Error('Digite de 1 a 1024 bytes de texto, sem quebras de linha ou caracteres de controle.');}
    const generation = this.inputGeneration;
    this.inputDiscretePending = true;
    try {await this.request(action); if (generation !== this.inputGeneration) {throw new Error('Controle interrompido.');}}
    finally {if (generation === this.inputGeneration) {this.inputDiscretePending = false;}}
  }

  beginVolume(value: number) {this.dragGeneration++; this.finalVolume = null; this.patch({draggingVolume: value}); this.throttleVolume(value);}
  updateVolume(value: number) {this.patch({draggingVolume: value}); this.throttleVolume(value);}
  endVolume(value: number) {
    this.patch({draggingVolume: Math.round(value)});
    if (this.delayedVolume) {clearTimeout(this.delayedVolume); this.delayedVolume = null;}
    const gen = this.generation;
    const drag = this.dragGeneration;
    const final = {value: Math.round(value), gen, drag, baseRevision: this.snapshot.state?.revision ?? -1, acked: false};
    this.finalVolume = final;
    this.request({kind: 'volume.set', percent: Math.round(value)}).then(() => {
      if (this.finalVolume !== final || gen !== this.generation || drag !== this.dragGeneration) {return;}
      final.acked = true;
      const current = this.snapshot.state;
      if (current && current.audio.percent === final.value && current.revision > final.baseRevision) {this.finalVolume = null; this.patch({draggingVolume: null});}
      else {this.request({kind: 'state.get'}).then(() => {if (this.finalVolume === final) {this.finalVolume = null; this.patch({draggingVolume: null});}}).catch(error => {if (this.finalVolume === final) {this.finalVolume = null; this.patch({draggingVolume: null, error: String(error.message)});}});}
    }).catch(error => {if (this.finalVolume === final) {this.finalVolume = null; this.patch({draggingVolume: null, error: String(error.message)});}});
  }
  private throttleVolume(value: number) {
    const now = Date.now();
    if (now - this.lastVolumeAt >= 100) {this.lastVolumeAt = now; this.request({kind: 'volume.set', percent: Math.round(value)}).catch(() => undefined);}
    else if (!this.delayedVolume) {this.delayedVolume = setTimeout(() => {this.delayedVolume = null; this.lastVolumeAt = Date.now(); if (this.snapshot.draggingVolume !== null) {this.request({kind: 'volume.set', percent: Math.round(this.snapshot.draggingVolume)}).catch(() => undefined);}}, 100 - (now - this.lastVolumeAt));}
  }
}
export const remote = new RemoteStore();
