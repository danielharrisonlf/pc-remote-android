import React, {type ReactNode} from 'react';
import {View} from 'react-native';
import {fingerprintSwatches} from '../../domain/fingerprint';
import {usePalette, useStyles} from '../../theme/ThemeProvider';
import {radius, space, type Palette} from '../../theme/tokens';
import {Icon, type IconName} from './Icon';
import {Txt} from './Txt';

export type StatusTone = 'success' | 'warning' | 'danger' | 'neutral' | 'accent';

/** Small colored dot with a label; color is never the only signal. */
export function StatusChip({tone, label}: {tone: StatusTone; label: string}) {
  const palette = usePalette();
  const styles = useStyles(makeStyles);
  const colors = {
    success: [palette.successSoft, palette.success],
    warning: [palette.warningSoft, palette.warning],
    danger: [palette.dangerSoft, palette.danger],
    accent: [palette.accentSoft, palette.accentText],
    neutral: [palette.surfaceSunken, palette.textSecondary],
  }[tone];
  return (
    <View style={[styles.chip, {backgroundColor: colors[0]}]}>
      <View style={[styles.dot, {backgroundColor: colors[1]}]} />
      <Txt variant="caption" style={{color: colors[1]}} numberOfLines={1}>
        {label}
      </Txt>
    </View>
  );
}

export function EmptyState({icon, title, children, action}: {icon: IconName; title: string; children?: ReactNode; action?: ReactNode}) {
  const palette = usePalette();
  const styles = useStyles(makeStyles);
  return (
    <View style={styles.empty}>
      <View style={styles.emptyIcon}>
        <Icon name={icon} size={26} color={palette.textTertiary} />
      </View>
      <Txt variant="title" center>
        {title}
      </Txt>
      {children ? (
        <Txt variant="body" tone="secondary" center style={styles.emptyText}>
          {children}
        </Txt>
      ) : null}
      {action ? <View style={styles.emptyAction}>{action}</View> : null}
    </View>
  );
}

/**
 * Same eight-color strip the Windows app draws next to its SHA-256. While the
 * value loads it keeps its size with neutral blocks, so nothing below shifts.
 */
export function FingerprintStrip({value, height = 16}: {value: string; height?: number}) {
  const palette = usePalette();
  const styles = useStyles(makeStyles);
  const swatches = fingerprintSwatches(value);
  const colors = swatches.length ? swatches : new Array<string>(8).fill(palette.border);
  return (
    <View style={styles.strip} accessibilityRole="image" accessibilityLabel={swatches.length ? 'Assinatura visual da identidade do PC' : 'Carregando identidade do PC'}>
      {colors.map((color, index) => (
        <View key={index} style={{width: height * 0.75, height, backgroundColor: color}} />
      ))}
    </View>
  );
}

export function Divider() {
  const styles = useStyles(makeStyles);
  return <View style={styles.divider} />;
}

const makeStyles = (palette: Palette) => ({
  chip: {
    flexDirection: 'row' as const,
    alignItems: 'center' as const,
    alignSelf: 'flex-start' as const,
    gap: 6,
    height: 28,
    paddingHorizontal: 10,
    borderRadius: radius.full,
  },
  dot: {width: 8, height: 8, borderRadius: 4},
  empty: {alignItems: 'center' as const, gap: space.sm, paddingVertical: space.xl, paddingHorizontal: space.lg},
  emptyIcon: {
    width: 56,
    height: 56,
    borderRadius: radius.lg,
    backgroundColor: palette.surfaceSunken,
    borderWidth: 1,
    borderColor: palette.border,
    alignItems: 'center' as const,
    justifyContent: 'center' as const,
    marginBottom: space.xs,
  },
  emptyText: {maxWidth: 320},
  emptyAction: {marginTop: space.md, alignSelf: 'stretch' as const},
  divider: {height: 1, backgroundColor: palette.border},
  strip: {flexDirection: 'row' as const, borderRadius: 4, overflow: 'hidden' as const, borderWidth: 1, borderColor: palette.border, alignSelf: 'flex-start' as const},
});
