import {useCallback, useEffect, useState, useSyncExternalStore} from 'react';
import {Vibration} from 'react-native';
import {discovery} from '../communication/discovery';
import {remote, type RemoteView} from '../communication/remote';
import {updates} from '../communication/updates';
import NativePCRemote from '../integrations/NativePCRemote';

export function useRemote(): RemoteView {
  return useSyncExternalStore(remote.subscribe, remote.getSnapshot);
}

/**
 * Subscribes to one primitive derived from the remote state, so components such
 * as the touchpad do not re-render on every volume or media update.
 */
export function useRemoteValue<T extends string | number | boolean | null>(select: (view: RemoteView) => T): T {
  return useSyncExternalStore(remote.subscribe, () => select(remote.getSnapshot()));
}

export function useUpdates() {
  return useSyncExternalStore(updates.subscribe, updates.getSnapshot);
}

/** Discovered PCs while `active`; discovery stops when no screen needs it. */
export function useDiscovery(active: boolean) {
  const found = useSyncExternalStore(discovery.subscribe, discovery.getSnapshot);
  useEffect(() => (active ? discovery.acquire() : undefined), [active]);
  return active ? found : [];
}

const PATTERNS = {tap: 10, confirm: 16, heavy: 28} as const;

/** Short vibrations that respect the "Feedback tátil" preference. */
export function useHaptics() {
  const enabled = useRemoteValue(view => view.haptics);
  return useCallback(
    (kind: keyof typeof PATTERNS = 'tap') => {
      if (enabled) {
        Vibration.vibrate(PATTERNS[kind]);
      }
    },
    [enabled],
  );
}

/** Round trip to the PC, refreshed every few seconds while `active`. */
export function useLatency(active: boolean): number | null {
  const [latency, setLatency] = useState<number | null>(null);
  useEffect(() => {
    if (!active) {
      setLatency(null);
      return;
    }
    let cancelled = false;
    const measure = () => {
      remote
        .ping()
        .then(ms => !cancelled && setLatency(ms))
        .catch(() => !cancelled && setLatency(null));
    };
    measure();
    const timer = setInterval(measure, 5000);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [active]);
  return latency;
}

export type SavedComputer = {host: string; port: number; fingerprint: string; pcId: string; pcName: string};

/** Endpoint stored by the native credential store; reloads when `key` changes. */
export function useSavedComputer(key: unknown): SavedComputer | null {
  const [saved, setSaved] = useState<SavedComputer | null>(null);
  useEffect(() => {
    let current = true;
    NativePCRemote.getSavedComputer()
      .then(raw => {
        if (!current) {
          return;
        }
        if (!raw) {
          setSaved(null);
          return;
        }
        const value = JSON.parse(raw) as Partial<SavedComputer>;
        setSaved({
          host: String(value.host ?? ''),
          port: Number(value.port ?? 0),
          fingerprint: String(value.fingerprint ?? ''),
          pcId: String(value.pcId ?? ''),
          pcName: String(value.pcName ?? ''),
        });
      })
      .catch(() => current && setSaved(null));
    return () => {
      current = false;
    };
  }, [key]);
  return saved;
}
