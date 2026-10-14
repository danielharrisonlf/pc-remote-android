import {TurboModuleRegistry, type TurboModule} from 'react-native';

export interface Spec extends TurboModule {
  connect(host: string, port: number, fingerprint: string, code: string, deviceName: string): void;
  reconnect(): void;
  disconnect(): void;
  sendRequest(id: string, actionJson: string): void;
  getSavedComputer(): Promise<string>;
  getPreferences(): Promise<string>;
  savePreferences(json: string): void;
  forgetComputer(): void;
  updateAddress(host: string, port: number): Promise<void>;
  readClipboard(): Promise<string>;
  writeClipboard(text: string): Promise<void>;
  scanQr(): Promise<string>;
  startDiscovery(): void;
  stopDiscovery(): void;
  getSharedText(): Promise<string>;
  getPendingRemoteMessage(): Promise<string>;
  getPendingFeedback(): Promise<string>;
  setForegroundSync(enabled: boolean): void;
  acknowledgeInputResume(): void;
  addListener(eventName: string): void;
  removeListeners(count: number): void;
  createId(): string;
}

export default TurboModuleRegistry.getEnforcing<Spec>('PCRemoteNative');
