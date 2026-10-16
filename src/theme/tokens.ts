// Color roles shared with the Windows app (src/styles/tokens.css in
// pc-remote-desktop). Dark is a derived scheme of the same roles: elevation is
// expressed by lighter surfaces, not shadows.

export type Palette = {
  scheme: 'light' | 'dark';
  bg: string;
  surface: string;
  surfaceSunken: string;
  surfaceHigh: string;
  border: string;
  borderStrong: string;
  text: string;
  textSecondary: string;
  textTertiary: string;
  accent: string;
  accentPressed: string;
  accentSoft: string;
  accentText: string;
  onAccent: string;
  success: string;
  successSoft: string;
  warning: string;
  warningSoft: string;
  danger: string;
  dangerSoft: string;
  onDanger: string;
  scrim: string;
  pad: string;
  padDot: string;
  ripple: string;
};

export const lightPalette: Palette = {
  scheme: 'light',
  bg: '#f3f5f8',
  surface: '#ffffff',
  surfaceSunken: '#eef1f5',
  surfaceHigh: '#ffffff',
  border: '#e2e7ec',
  borderStrong: '#cbd3db',
  text: '#0f172a',
  textSecondary: '#4b5866',
  textTertiary: '#66727f',
  accent: '#4f50d6',
  accentPressed: '#393aa8',
  accentSoft: '#e6e8fd',
  accentText: '#3e3fb8',
  onAccent: '#ffffff',
  success: '#12804f',
  successSoft: '#e5f5ec',
  warning: '#8f5300',
  warningSoft: '#fdf3e3',
  danger: '#c0352b',
  dangerSoft: '#fdeeed',
  onDanger: '#ffffff',
  scrim: 'rgba(15, 23, 42, 0.42)',
  pad: '#e9edf2',
  padDot: '#c2cbd5',
  ripple: 'rgba(79, 80, 214, 0.14)',
};

export const darkPalette: Palette = {
  scheme: 'dark',
  bg: '#0e1114',
  surface: '#161a1f',
  surfaceSunken: '#121519',
  surfaceHigh: '#1f242b',
  border: '#252b32',
  borderStrong: '#353d46',
  text: '#e8ecf0',
  textSecondary: '#a4aeb9',
  textTertiary: '#87919c',
  accent: '#9fa3ff',
  accentPressed: '#8e92f0',
  accentSoft: '#23264a',
  accentText: '#b6b9ff',
  onAccent: '#12133a',
  success: '#5ad497',
  successSoft: '#14261e',
  warning: '#f2b862',
  warningSoft: '#2a2114',
  danger: '#ff8f84',
  dangerSoft: '#2c1717',
  onDanger: '#2a0a07',
  scrim: 'rgba(0, 0, 0, 0.6)',
  pad: '#13171b',
  padDot: '#2b323a',
  ripple: 'rgba(159, 163, 255, 0.16)',
};

export const space = {xs: 4, sm: 8, md: 12, lg: 16, xl: 20, xxl: 24, xxxl: 32} as const;
export const radius = {sm: 8, md: 12, lg: 16, xl: 20, xxl: 28, full: 999} as const;
/** Minimum touch target (Material: 48dp). */
export const TOUCH = 48;

export const typeScale = {
  display: {fontSize: 48, lineHeight: 56, fontWeight: '700', letterSpacing: -1},
  headline: {fontSize: 24, lineHeight: 30, fontWeight: '700', letterSpacing: -0.2},
  title: {fontSize: 18, lineHeight: 24, fontWeight: '600'},
  body: {fontSize: 15, lineHeight: 22, fontWeight: '400'},
  bodyStrong: {fontSize: 15, lineHeight: 22, fontWeight: '600'},
  label: {fontSize: 14, lineHeight: 20, fontWeight: '600'},
  caption: {fontSize: 12, lineHeight: 16, fontWeight: '500'},
  mono: {fontSize: 14, lineHeight: 20, fontFamily: 'monospace'},
} as const;

export type TypeVariant = keyof typeof typeScale;
