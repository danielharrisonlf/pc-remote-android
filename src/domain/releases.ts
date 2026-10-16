import {compareVersions, displayVersion, parseVersion} from './semver';

export type ReleaseAsset = {name: string; size: number; downloadUrl: string};

export type ReleaseInfo = {
  tag: string;
  version: string;
  title: string;
  notes: string;
  publishedAt: string | null;
  pageUrl: string;
  assets: ReleaseAsset[];
};

export type ReleaseLookup =
  | {kind: 'found'; release: ReleaseInfo}
  | {kind: 'none'}
  | {kind: 'unavailable'; reason: 'not-found' | 'rate-limited' | 'network' | 'invalid'; message: string; retryAt?: number};

export type FetchReleaseOptions = {
  /** Accepts the tags that belong to this app. Defaults to plain `vX.Y.Z` tags. */
  acceptTag?: (tag: string) => boolean;
  fetchImpl?: typeof fetch;
};

const API = 'https://api.github.com/repos';
/** React Native releases use plain tags; the Kotlin alternative uses `-kotlin`. */
export const stableTag = (tag: string) => /^v?\d+\.\d+\.\d+$/.test(tag.trim());

function text(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

export function parseRelease(raw: unknown): ReleaseInfo | null {
  if (!raw || typeof raw !== 'object') {
    return null;
  }
  const value = raw as Record<string, unknown>;
  const tag = text(value.tag_name).trim();
  const pageUrl = text(value.html_url);
  if (!tag || !parseVersion(tag) || !pageUrl.startsWith('https://github.com/')) {
    return null;
  }
  const assets = Array.isArray(value.assets)
    ? value.assets.flatMap(item => {
        if (!item || typeof item !== 'object') {
          return [];
        }
        const asset = item as Record<string, unknown>;
        const downloadUrl = text(asset.browser_download_url);
        const name = text(asset.name);
        if (!name || !downloadUrl.startsWith('https://github.com/')) {
          return [];
        }
        return [{name, downloadUrl, size: typeof asset.size === 'number' ? asset.size : 0}];
      })
    : [];
  return {
    tag,
    version: displayVersion(tag),
    title: text(value.name).trim() || `Versão ${displayVersion(tag)}`,
    notes: text(value.body).replace(/\r\n/g, '\n').trim(),
    publishedAt: text(value.published_at) || null,
    pageUrl,
    assets,
  };
}

async function request(url: string, options: FetchReleaseOptions): Promise<{response?: Response; failure?: ReleaseLookup}> {
  const fetchImpl = options.fetchImpl ?? fetch;
  try {
    const response = await fetchImpl(url, {headers: {Accept: 'application/vnd.github+json'}});
    if (response.status === 403 || response.status === 429) {
      const reset = Number(response.headers.get('x-ratelimit-reset'));
      return {
        failure: {
          kind: 'unavailable',
          reason: 'rate-limited',
          message: 'O GitHub limitou as consultas desta rede. A verificação será repetida mais tarde.',
          retryAt: Number.isFinite(reset) && reset > 0 ? reset * 1000 : undefined,
        },
      };
    }
    return {response};
  } catch {
    return {failure: {kind: 'unavailable', reason: 'network', message: 'Sem acesso ao GitHub. Confira a conexão com a internet.'}};
  }
}

async function readJson(response: Response): Promise<unknown> {
  try {
    return await response.json();
  } catch {
    return undefined;
  }
}

/**
 * Reads `/releases/latest` and falls back to the release list when the latest
 * release belongs to another flavor (for example a `-kotlin` tag).
 */
export async function fetchLatestRelease(repo: string, options: FetchReleaseOptions = {}): Promise<ReleaseLookup> {
  const accept = options.acceptTag ?? stableTag;
  const latest = await request(`${API}/${repo}/releases/latest`, options);
  if (latest.failure) {
    return latest.failure;
  }
  const response = latest.response!;
  if (response.status === 404) {
    return {kind: 'unavailable', reason: 'not-found', message: 'Nenhuma versão pública foi encontrada para este aplicativo.'};
  }
  if (!response.ok) {
    return {kind: 'unavailable', reason: 'network', message: `O GitHub respondeu com o código ${response.status}.`};
  }
  const release = parseRelease(await readJson(response));
  if (!release) {
    return {kind: 'unavailable', reason: 'invalid', message: 'A release mais recente não tem uma versão válida.'};
  }
  if (accept(release.tag)) {
    return {kind: 'found', release};
  }

  const list = await request(`${API}/${repo}/releases?per_page=30`, options);
  if (list.failure) {
    return list.failure;
  }
  if (!list.response!.ok) {
    return {kind: 'none'};
  }
  const items = await readJson(list.response!);
  const candidates = (Array.isArray(items) ? items : [])
    .filter(item => item && typeof item === 'object' && !(item as {draft?: boolean}).draft && !(item as {prerelease?: boolean}).prerelease)
    .map(parseRelease)
    .filter((item): item is ReleaseInfo => item !== null && accept(item.tag))
    .sort((a, b) => compareVersions(parseVersion(b.tag)!, parseVersion(a.tag)!));
  return candidates[0] ? {kind: 'found', release: candidates[0]} : {kind: 'none'};
}

export type Abi = 'arm64' | 'x86_64';

/** Emulators report generic or SDK builds; everything else is treated as a phone. */
export function detectAbi(constants: {Fingerprint?: string; Model?: string}): Abi {
  const fingerprint = (constants.Fingerprint ?? '').toLowerCase();
  const model = (constants.Model ?? '').toLowerCase();
  const emulator = fingerprint.startsWith('generic') || fingerprint.includes('/sdk_') || model.includes('sdk_gphone') || model.includes('emulator') || model.includes('android sdk built for');
  return emulator ? 'x86_64' : 'arm64';
}

/** APK for this device's ABI, then any APK. Kotlin builds never match the RN app. */
export function pickApk(assets: ReleaseAsset[], abi: Abi): ReleaseAsset | undefined {
  const apks = assets.filter(asset => /\.apk$/i.test(asset.name) && !/kotlin/i.test(asset.name));
  return apks.find(asset => asset.name.toLowerCase().includes(`-${abi}`)) ?? apks.find(asset => !/x86/i.test(asset.name)) ?? apks[0];
}
