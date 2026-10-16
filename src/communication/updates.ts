import {AppState, Linking, Platform} from 'react-native';
import {version as installedVersion} from '../../package.json';
import {detectAbi, fetchLatestRelease, pickApk, type ReleaseAsset, type ReleaseInfo, type ReleaseLookup} from '../domain/releases';
import {isNewerVersion} from '../domain/semver';

export const UPDATE_REPOSITORY = 'danielharrisonlf/pc-remote-android';
/** Keep in sync with versionName in android/app/build.gradle. */
export const APP_VERSION = installedVersion;

const CHECK_EVERY = 6 * 60 * 60 * 1000;
const FIRST_CHECK_DELAY = 4000;

export type UpdateSnapshot = {
  checking: boolean;
  checkedAt: number | null;
  lookup: ReleaseLookup | null;
  release: ReleaseInfo | null;
  apk: ReleaseAsset | undefined;
  available: boolean;
};

type Listener = () => void;

/**
 * Checks GitHub Releases on start and every six hours while the app is in the
 * foreground. Downloads open in the browser, which hands the APK to the system
 * installer; nothing is installed silently.
 */
export class UpdateStore {
  private listeners = new Set<Listener>();
  private inFlight: Promise<ReleaseLookup> | null = null;
  private started = false;
  snapshot: UpdateSnapshot = {checking: false, checkedAt: null, lookup: null, release: null, apk: undefined, available: false};

  constructor(
    private readonly currentVersion = APP_VERSION,
    private readonly fetchImpl?: typeof fetch,
  ) {}

  subscribe = (listener: Listener) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };
  getSnapshot = () => this.snapshot;

  private patch(change: Partial<UpdateSnapshot>) {
    this.snapshot = {...this.snapshot, ...change};
    this.listeners.forEach(listener => listener());
  }

  check(): Promise<ReleaseLookup> {
    if (this.inFlight) {
      return this.inFlight;
    }
    this.patch({checking: true});
    this.inFlight = fetchLatestRelease(UPDATE_REPOSITORY, {fetchImpl: this.fetchImpl})
      .catch((): ReleaseLookup => ({kind: 'unavailable', reason: 'invalid', message: 'Não foi possível verificar as atualizações.'}))
      .then(lookup => {
        const release = lookup.kind === 'found' ? lookup.release : null;
        const abi = Platform.OS === 'android' ? detectAbi(Platform.constants as {Fingerprint?: string; Model?: string}) : 'arm64';
        this.patch({
          checking: false,
          checkedAt: Date.now(),
          lookup,
          release,
          apk: release ? pickApk(release.assets, abi) : undefined,
          available: Boolean(release && isNewerVersion(release.version, this.currentVersion)),
        });
        return lookup;
      })
      .finally(() => {
        this.inFlight = null;
      });
    return this.inFlight;
  }

  /** Starts automatic checks; returns a cleanup function. */
  startAutomatic(): () => void {
    if (this.started) {
      return () => undefined;
    }
    this.started = true;
    const due = () => !this.snapshot.checkedAt || Date.now() - this.snapshot.checkedAt >= CHECK_EVERY;
    const first = setTimeout(() => this.check(), FIRST_CHECK_DELAY);
    const interval = setInterval(() => {
      if (AppState.currentState === 'active' && due()) {
        this.check();
      }
    }, 15 * 60 * 1000);
    const subscription = AppState.addEventListener('change', state => {
      if (state === 'active' && due()) {
        this.check();
      }
    });
    return () => {
      this.started = false;
      clearTimeout(first);
      clearInterval(interval);
      subscription.remove();
    };
  }

  /** Opens the APK (or the release page) in the browser. */
  async download(): Promise<void> {
    const {apk, release} = this.snapshot;
    const url = apk?.downloadUrl ?? release?.pageUrl;
    if (!url || !url.startsWith('https://github.com/')) {
      throw new Error('Nenhum download disponível.');
    }
    await Linking.openURL(url);
  }

  async openReleasePage(): Promise<void> {
    const url = this.snapshot.release?.pageUrl;
    if (url) {
      await Linking.openURL(url);
    }
  }
}

export const updates = new UpdateStore();
