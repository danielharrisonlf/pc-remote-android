import React from 'react';
import {ActivityIndicator, Pressable, StyleSheet, View, type StyleProp, type ViewStyle} from 'react-native';
import {usePalette} from '../../theme/ThemeProvider';
import {radius, TOUCH, type Palette} from '../../theme/tokens';
import {Icon, type IconName} from './Icon';
import {Txt} from './Txt';

export type ButtonVariant = 'filled' | 'tonal' | 'outlined' | 'text' | 'danger';

function colors(palette: Palette, variant: ButtonVariant) {
  switch (variant) {
    case 'filled':
      return {bg: palette.accent, fg: palette.onAccent, border: 'transparent'};
    case 'tonal':
      return {bg: palette.accentSoft, fg: palette.accentText, border: 'transparent'};
    case 'outlined':
      return {bg: 'transparent', fg: palette.text, border: palette.borderStrong};
    case 'danger':
      return {bg: palette.dangerSoft, fg: palette.danger, border: 'transparent'};
    case 'text':
      return {bg: 'transparent', fg: palette.accentText, border: 'transparent'};
  }
}

type ButtonProps = {
  label: string;
  onPress: () => void;
  variant?: ButtonVariant;
  icon?: IconName;
  size?: 'md' | 'lg';
  disabled?: boolean;
  loading?: boolean;
  /** Stretches to fill the row. */
  grow?: boolean;
  accessibilityHint?: string;
  style?: StyleProp<ViewStyle>;
};

export function Button({label, onPress, variant = 'filled', icon, size = 'md', disabled, loading, grow, accessibilityHint, style}: ButtonProps) {
  const palette = usePalette();
  const {bg, fg, border} = colors(palette, variant);
  const inactive = disabled || loading;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityHint={accessibilityHint}
      accessibilityState={{disabled: !!inactive, busy: !!loading}}
      disabled={inactive}
      onPress={onPress}
      android_ripple={{color: palette.ripple, borderless: false}}
      style={({pressed}) => [
        styles.base,
        size === 'lg' ? styles.large : null,
        grow ? styles.grow : null,
        {backgroundColor: bg, borderColor: border, opacity: disabled ? 0.45 : 1},
        pressed && !inactive ? styles.pressed : null,
        style,
      ]}>
      <View style={styles.content}>
        {loading ? <ActivityIndicator size="small" color={fg} /> : icon ? <Icon name={icon} size={20} color={fg} /> : null}
        <Txt variant="label" style={[styles.label, {color: fg}]} numberOfLines={1}>
          {label}
        </Txt>
      </View>
    </Pressable>
  );
}

type IconButtonProps = {
  icon: IconName;
  label: string;
  onPress: () => void;
  variant?: 'standard' | 'tonal' | 'filled';
  size?: number;
  disabled?: boolean;
  selected?: boolean;
};

/** 48dp minimum target; the visible container can be smaller or larger. */
export function IconButton({icon, label, onPress, variant = 'standard', size = TOUCH, disabled, selected}: IconButtonProps) {
  const palette = usePalette();
  const bg = variant === 'filled' ? palette.accent : variant === 'tonal' || selected ? palette.accentSoft : 'transparent';
  const fg = variant === 'filled' ? palette.onAccent : variant === 'tonal' || selected ? palette.accentText : palette.textSecondary;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{disabled: !!disabled, selected}}
      disabled={disabled}
      onPress={onPress}
      hitSlop={size < TOUCH ? (TOUCH - size) / 2 : undefined}
      android_ripple={{color: palette.ripple, borderless: true, radius: size / 2}}
      style={({pressed}) => [
        styles.iconButton,
        {width: size, height: size, borderRadius: size / 2, backgroundColor: bg, opacity: disabled ? 0.45 : 1},
        pressed ? styles.pressed : null,
      ]}>
      <Icon name={icon} size={Math.round(size * 0.46)} color={fg} />
    </Pressable>
  );
}

const styles = StyleSheet.create({
  base: {
    minHeight: TOUCH,
    paddingHorizontal: 20,
    borderRadius: radius.full,
    borderWidth: 1,
    justifyContent: 'center',
    overflow: 'hidden',
  },
  large: {minHeight: 56, paddingHorizontal: 24},
  grow: {flex: 1},
  content: {flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 8},
  label: {flexShrink: 1},
  pressed: {transform: [{scale: 0.97}]},
  iconButton: {alignItems: 'center', justifyContent: 'center', overflow: 'hidden'},
});
