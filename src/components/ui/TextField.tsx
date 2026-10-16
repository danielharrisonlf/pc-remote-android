import React, {useState} from 'react';
import {TextInput, View, type TextInputProps} from 'react-native';
import {usePalette, useStyles} from '../../theme/ThemeProvider';
import {radius, space, type Palette} from '../../theme/tokens';
import {Txt} from './Txt';

type TextFieldProps = Omit<TextInputProps, 'style'> & {label: string; hint?: string; error?: string | null; mono?: boolean};

/** Outlined Material text field with a persistent label above the input. */
export function TextField({label, hint, error, mono, onFocus, onBlur, ...input}: TextFieldProps) {
  const palette = usePalette();
  const styles = useStyles(makeStyles);
  const [focused, setFocused] = useState(false);
  return (
    <View style={styles.wrap}>
      <Txt variant="caption" tone={error ? 'danger' : focused ? 'accent' : 'secondary'}>
        {label}
      </Txt>
      <TextInput
        accessibilityLabel={label}
        accessibilityHint={error ?? hint}
        placeholderTextColor={palette.textTertiary}
        selectionColor={palette.accent}
        cursorColor={palette.accent}
        autoCorrect={false}
        autoCapitalize="none"
        {...input}
        onFocus={event => {
          setFocused(true);
          onFocus?.(event);
        }}
        onBlur={event => {
          setFocused(false);
          onBlur?.(event);
        }}
        style={[styles.input, mono ? styles.mono : null, {borderColor: error ? palette.danger : focused ? palette.accent : palette.borderStrong}, focused ? styles.focused : null]}
      />
      {error || hint ? (
        <Txt variant="caption" tone={error ? 'danger' : 'tertiary'}>
          {error ?? hint}
        </Txt>
      ) : null}
    </View>
  );
}

const makeStyles = (palette: Palette) => ({
  wrap: {gap: 6},
  input: {
    minHeight: 52,
    paddingHorizontal: space.lg,
    borderWidth: 1,
    borderRadius: radius.md,
    backgroundColor: palette.surface,
    color: palette.text,
    fontSize: 16,
  },
  focused: {borderWidth: 2, paddingHorizontal: space.lg - 1},
  mono: {fontFamily: 'monospace', fontSize: 14},
});
