import React from 'react';
import {Pressable, Switch, Text, TextInput, View} from 'react-native';
import {dark, light, styles, type Palette} from '../theme/theme';

export function Field({label, value, onChange, palette, numeric = false}: {label: string; value: string; onChange: (text: string) => void; palette: Palette; numeric?: boolean}) {
  return <View><Text style={[styles.fieldLabel, {color: palette.soft}]}>{label}</Text><TextInput accessibilityLabel={label} value={value} onChangeText={onChange} keyboardType={numeric ? 'number-pad' : 'default'} autoCapitalize="none" autoCorrect={false} style={[styles.input, {color: palette.ink, borderColor: palette.line}]} /></View>;
}

export function Button({label, onPress, disabled, secondary, palette, hint}: {label: string; onPress: () => void; disabled?: boolean; secondary?: boolean; palette: Palette; hint?: string}) {
  return <View style={{flex: 1}}><Pressable accessibilityRole="button" accessibilityLabel={label} accessibilityHint={hint} disabled={disabled} onPress={onPress}
    style={({pressed}) => [styles.button, {backgroundColor: secondary ? palette.accentPale : palette.accent, opacity: disabled ? 0.4 : pressed ? 0.78 : 1}]}>
    <Text style={[styles.buttonText, {color: secondary ? palette.ink : palette === dark ? light.ink : dark.ink}]}>{label}</Text></Pressable>{disabled && hint ? <Text style={[styles.hint, {color: palette.soft}]}>{hint}</Text> : null}</View>;
}

export function Card({children, title, palette}: {children: React.ReactNode; title: string; palette: Palette}) {
  return <View style={[styles.card, {backgroundColor: palette.surface, borderColor: palette.line}]}><Text style={[styles.cardTitle, {color: palette.ink}]}>{title}</Text>{children}</View>;
}

export function Setting({label, detail, value, onValueChange, palette}: {label: string; detail: string; value: boolean; onValueChange: (value: boolean) => void; palette: Palette}) {
  return <View style={[styles.setting, {borderBottomColor: palette.line}]}><View style={{flex: 1}}><Text style={[styles.label, {color: palette.ink}]}>{label}</Text><Text style={[styles.hint, {color: palette.soft}]}>{detail}</Text></View><Switch accessibilityLabel={label} value={value} onValueChange={onValueChange} trackColor={{true: palette.accent, false: palette.line}} /></View>;
}
