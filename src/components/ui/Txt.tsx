import React from 'react';
import {StyleSheet, Text, type TextProps} from 'react-native';
import {usePalette} from '../../theme/ThemeProvider';
import {typeScale, type Palette, type TypeVariant} from '../../theme/tokens';

export type Tone = 'primary' | 'secondary' | 'tertiary' | 'accent' | 'success' | 'warning' | 'danger' | 'onAccent';

const toneColor = (palette: Palette, tone: Tone) =>
  ({
    primary: palette.text,
    secondary: palette.textSecondary,
    tertiary: palette.textTertiary,
    accent: palette.accentText,
    success: palette.success,
    warning: palette.warning,
    danger: palette.danger,
    onAccent: palette.onAccent,
  })[tone];

type TxtProps = TextProps & {variant?: TypeVariant; tone?: Tone; tabular?: boolean; center?: boolean};

/** Text bound to the type scale and color roles. Font size follows the system setting. */
export function Txt({variant = 'body', tone = 'primary', tabular, center, style, ...rest}: TxtProps) {
  const palette = usePalette();
  return (
    <Text
      {...rest}
      style={[
        typeScale[variant],
        {color: toneColor(palette, tone)},
        tabular ? {fontVariant: ['tabular-nums']} : null,
        center ? styles.center : null,
        style,
      ]}
    />
  );
}

const styles = StyleSheet.create({center: {textAlign: 'center'}});
