import React from 'react';
import {Pressable, Switch, View} from 'react-native';
import {usePalette, useStyles} from '../../theme/ThemeProvider';
import {radius, space, type Palette} from '../../theme/tokens';
import {Icon, type IconName} from './Icon';
import {Txt} from './Txt';

type SwitchRowProps = {
  label: string;
  description?: string;
  value: boolean;
  onValueChange: (value: boolean) => void;
  icon?: IconName;
  disabled?: boolean;
  last?: boolean;
};

/** Whole row toggles, so the target is the full width rather than the switch alone. */
export function SwitchRow({label, description, value, onValueChange, icon, disabled, last}: SwitchRowProps) {
  const palette = usePalette();
  const styles = useStyles(makeStyles);
  return (
    <Pressable
      accessibilityRole="switch"
      accessibilityLabel={label}
      accessibilityHint={description}
      accessibilityState={{checked: value, disabled: !!disabled}}
      disabled={disabled}
      onPress={() => onValueChange(!value)}
      android_ripple={{color: palette.ripple}}
      style={[styles.row, last ? null : styles.divider, disabled ? styles.disabled : null]}>
      {icon ? (
        <View style={styles.icon}>
          <Icon name={icon} size={18} color={palette.textSecondary} />
        </View>
      ) : null}
      <View style={styles.text}>
        <Txt variant="bodyStrong">{label}</Txt>
        {description ? (
          <Txt variant="caption" tone="secondary" style={styles.description}>
            {description}
          </Txt>
        ) : null}
      </View>
      <Switch
        importantForAccessibility="no-hide-descendants"
        value={value}
        disabled={disabled}
        onValueChange={onValueChange}
        trackColor={{true: palette.accent, false: palette.borderStrong}}
        thumbColor={value ? palette.onAccent : palette.surfaceHigh}
      />
    </Pressable>
  );
}

const makeStyles = (palette: Palette) => ({
  row: {flexDirection: 'row' as const, alignItems: 'center' as const, gap: space.md, minHeight: 64, paddingVertical: space.md},
  divider: {borderBottomWidth: 1, borderBottomColor: palette.border},
  disabled: {opacity: 0.5},
  icon: {
    width: 36,
    height: 36,
    borderRadius: radius.md,
    backgroundColor: palette.surfaceSunken,
    alignItems: 'center' as const,
    justifyContent: 'center' as const,
  },
  text: {flex: 1, gap: 2},
  description: {fontSize: 13, lineHeight: 18, fontWeight: '400' as const},
});
