import {NativeEventEmitter, type EmitterSubscription} from 'react-native';
import NativePCRemote from '../integrations/NativePCRemote';

export type DiscoveredPc = {name: string; host: string; port: number};
type Listener = () => void;

/**
 * Single owner of native mDNS discovery. Screens acquire it while they need
 * results; the native search stops only when the last user releases it, so one
 * screen closing cannot stop discovery another one depends on. While it runs,
 * the native side also reconnects to the saved PC when it reappears.
 */
class DiscoveryStore {
  private users = 0;
  private subscription: EmitterSubscription | null = null;
  private listeners = new Set<Listener>();
  found: DiscoveredPc[] = [];

  subscribe = (listener: Listener) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };
  getSnapshot = () => this.found;

  acquire(): () => void {
    if (this.users++ === 0) {
      this.found = [];
      this.subscription = new NativeEventEmitter(NativePCRemote).addListener('remote.discovery', raw => this.onFound(raw as string));
      NativePCRemote.startDiscovery();
    }
    let released = false;
    return () => {
      if (released) {
        return;
      }
      released = true;
      if (--this.users === 0) {
        this.subscription?.remove();
        this.subscription = null;
        NativePCRemote.stopDiscovery();
      }
    };
  }

  private onFound(raw: string) {
    try {
      const item = JSON.parse(raw) as Partial<DiscoveredPc>;
      if (typeof item.host !== 'string' || typeof item.port !== 'number') {
        return;
      }
      const entry = {name: typeof item.name === 'string' && item.name ? item.name : item.host, host: item.host, port: item.port};
      this.found = [...this.found.filter(pc => pc.host !== entry.host), entry];
      this.listeners.forEach(listener => listener());
    } catch {
      // Ignore malformed discovery events; manual entry is always available.
    }
  }
}

export const discovery = new DiscoveryStore();
