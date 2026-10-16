import {ScrollMotion} from '../src/communication/ScrollMotion';
import {deviceDisplayName} from '../src/domain/device';
import {fingerprintGroups, fingerprintShort, fingerprintSwatches} from '../src/domain/fingerprint';
import {parseMarkdown} from '../src/domain/markdown';
import {utf8Size} from '../src/domain/protocol';
import {detectAbi, fetchLatestRelease, pickApk} from '../src/domain/releases';
import {compareVersions, isNewerVersion, parseVersion} from '../src/domain/semver';

// sha256('pc-remote'); the Windows app asserts the same goldens in src/lib/display.test.ts.
const digest = '6fab114e66015573129c77331ae7a7a6f891e79d840750f32bcd7a3d55bd3d53';

test('fingerprint groups and colors match the Windows app exactly', () => {
  expect(fingerprintGroups(digest)).toHaveLength(16);
  expect(fingerprintShort(digest)).toBe('6FAB 114E … 3D53');
  expect(fingerprintSwatches(digest)).toEqual([
    'hsl(245, 64%, 46%)',
    'hsl(150, 64%, 58%)',
    'hsl(353, 64%, 58%)',
    'hsl(311, 64%, 58%)',
    'hsl(243, 64%, 58%)',
    'hsl(129, 64%, 58%)',
    'hsl(224, 64%, 46%)',
    'hsl(165, 64%, 46%)',
  ]);
  expect(fingerprintSwatches(`${digest.slice(0, -2)}54`)).not.toEqual(fingerprintSwatches(digest));
});

test('scroll steps are batched with one request in flight and capped per request', async () => {
  const sent: number[] = [];
  let release: () => void = () => undefined;
  const motion = new ScrollMotion(
    steps => {
      sent.push(steps);
      return new Promise(resolve => {
        release = resolve;
      });
    },
    () => true,
    () => undefined,
  );
  motion.add(2);
  motion.add(5);
  motion.add(9);
  expect(sent).toEqual([2]);
  release();
  await Promise.resolve();
  await Promise.resolve();
  expect(sent).toEqual([2, 10]);
  release();
  await Promise.resolve();
  await Promise.resolve();
  expect(sent).toEqual([2, 10, 4]);
});

test('scroll motion drops pending steps on cancel and ignores input while inactive', async () => {
  const sent: number[] = [];
  let active = true;
  let release: () => void = () => undefined;
  const motion = new ScrollMotion(
    steps => {
      sent.push(steps);
      return new Promise(resolve => {
        release = resolve;
      });
    },
    () => active,
    () => undefined,
  );
  motion.add(-3);
  motion.add(-3);
  motion.cancel();
  release();
  await Promise.resolve();
  await Promise.resolve();
  expect(sent).toEqual([-3]);
  active = false;
  motion.add(4);
  expect(sent).toEqual([-3]);
  motion.add(0.4);
  expect(sent).toEqual([-3]);
});

test('scroll failure reports once and clears the queue', async () => {
  const failed = jest.fn();
  const motion = new ScrollMotion(() => Promise.reject(new Error('rate_limited')), () => true, failed);
  motion.add(3);
  await Promise.resolve();
  await Promise.resolve();
  expect(failed).toHaveBeenCalledWith(expect.objectContaining({message: 'rate_limited'}));
});

test('pairing requests carry a readable device name within the PC limit', () => {
  expect(deviceDisplayName({Brand: 'samsung', Model: 'SM-S918B'})).toBe('Samsung SM-S918B');
  expect(deviceDisplayName({Brand: 'google', Model: 'Pixel 8'})).toBe('Google Pixel 8');
  expect(deviceDisplayName({Brand: 'motorola', Model: 'motorola edge 40'})).toBe('motorola edge 40');
  expect(deviceDisplayName({})).toBe('Celular Android');
  const long = deviceDisplayName({Brand: 'X', Model: 'ç'.repeat(100)});
  expect(utf8Size(long)).toBeLessThanOrEqual(80);
  expect(long.startsWith('X ç')).toBe(true);
});

test('semantic versions compare numerically and rank prereleases lower', () => {
  expect(compareVersions(parseVersion('0.10.0')!, parseVersion('0.9.9')!)).toBe(1);
  expect(compareVersions(parseVersion('1.0.0-rc.1')!, parseVersion('1.0.0')!)).toBe(-1);
  expect(isNewerVersion('v0.3.0', '0.2.1')).toBe(true);
  expect(isNewerVersion('v0.2.1', '0.2.1')).toBe(false);
});

const asset = (name: string) => ({name, size: 20_000_000, downloadUrl: `https://github.com/danielharrisonlf/pc-remote-android/releases/download/v0.3.0/${name}`});

test('picks the APK for this device ABI and never the Kotlin build', () => {
  const assets = [
    asset('ANDROID-SHA256SUMS-0.3.0.txt'),
    asset('pc-remote-android-kotlin-0.3.0-evaluation.apk'),
    asset('pc-remote-android-0.3.0-x86_64-evaluation.apk'),
    asset('pc-remote-android-0.3.0-arm64-evaluation.apk'),
  ];
  expect(pickApk(assets, 'arm64')?.name).toBe('pc-remote-android-0.3.0-arm64-evaluation.apk');
  expect(pickApk(assets, 'x86_64')?.name).toBe('pc-remote-android-0.3.0-x86_64-evaluation.apk');
  expect(detectAbi({Fingerprint: 'google/sdk_gphone64_x86_64/emu64xa:14/UE1A', Model: 'sdk_gphone64_x86_64'})).toBe('x86_64');
  expect(detectAbi({Fingerprint: 'samsung/dm3qxx/dm3q:14/UP1A', Model: 'SM-S918B'})).toBe('arm64');
});

const release = (tag: string, extra: object = {}) => ({
  tag_name: tag,
  name: tag,
  body: '- Novidades',
  html_url: `https://github.com/danielharrisonlf/pc-remote-android/releases/tag/${tag}`,
  draft: false,
  prerelease: false,
  assets: [],
  ...extra,
});

const reply = (body: unknown, status = 200) => ({ok: status >= 200 && status < 300, status, headers: {get: () => null}, json: async () => body}) as unknown as Response;

test('skips Kotlin releases even when GitHub marks one as latest', async () => {
  const fetchImpl = jest.fn(async (url: string) =>
    url.endsWith('/latest') ? reply(release('v0.3.0-kotlin')) : reply([release('v0.3.0-kotlin'), release('v0.2.9', {prerelease: true}), release('v0.2.1'), release('v0.1.0')]),
  ) as unknown as typeof fetch;
  const result = await fetchLatestRelease('owner/repo', {fetchImpl});
  expect(result.kind === 'found' && result.release.version).toBe('0.2.1');
});

test('reports a missing feed and network errors without throwing', async () => {
  expect(await fetchLatestRelease('owner/repo', {fetchImpl: (async () => reply({}, 404)) as unknown as typeof fetch})).toMatchObject({kind: 'unavailable', reason: 'not-found'});
  expect(
    await fetchLatestRelease('owner/repo', {
      fetchImpl: (async () => {
        throw new TypeError('Network request failed');
      }) as unknown as typeof fetch,
    }),
  ).toMatchObject({kind: 'unavailable', reason: 'network'});
});

test('release notes become plain data, never markup', () => {
  const blocks = parseMarkdown('## Novidades\n\n- **Touchpad** em tela cheia\n- Abas\n\n<b>x</b>');
  expect(blocks.map(block => block.type)).toEqual(['heading', 'list', 'paragraph']);
  expect(blocks[2]).toEqual({type: 'paragraph', children: [{type: 'text', text: '<b>x</b>'}]});
});
