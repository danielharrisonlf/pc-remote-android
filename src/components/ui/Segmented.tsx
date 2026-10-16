import React from 'react';
import {Pressable, View} from 'react-native';
import {usePalette, useStyles} from '../../theme/ThemeProvider';
import {radius, type Palette} from '../../theme/tokens';
import {Icon, type IconName} from './Icon';
import {Txt} from './Txt';

type Option<T extends string> = {value: T; label: string; icon?: IconName};

/** Material 3 segmented buttons for a single choice. */
export function Segmented<T extends string>({label, value, options, onChange}: {label: string; value: T; options: Option<T>[]; onChange: (value: T) => void}) {
  const palette = usePalette();
  const styles = useStyles(makeStyles);
  return (
    <View style={styles.group} accessibilityRole="radiogroup" accessibilityLabel={label}>
      {options.map((option, index) => {
        const selected = option.value === value;
        return (
          <Pressable
            key={option.value}
            accessibilityRole="radio"
            accessibilityState={{checked: selected}}
            accessibilityLabel={option.label}
            onPress={() => onChange(option.value)}
            android_ripple={{color: palette.ripple}}
            style={[styles.segment, index > 0 ? styles.separator : null, selected ? styles.selected : null]}>
            {selected ? <Icon name="check" size={16} color={palette.accentText} /> : option.icon ? <Icon name={option.icon} size={16} color={palette.textSecondary} /> : null}
            <Txt variant="label" tone={selected ? 'accent' : 'secondary'} numberOfLines={1}>
              {option.label}
            </Txt>
          </Pressable>
        );
      })}
    </View>
  );
}

const makeStyles = (palette: Palette) => ({
  group: {
    flexDirection: 'row' as const,
    borderWidth: 1,
    borderColor: palette.borderStrong,
    borderRadius: radius.full,
    overflow: 'hidden' as const,
  },
  segment: {
    flex: 1,
    minHeight: 48,
    flexDirection: 'row' as const,
    alignItems: 'center' as const,
    justifyContent: 'center' as const,
    gap: 6,
    paddingHorizontal: 8,
  },
  separator: {borderLeftWidth: 1, borderLeftColor: palette.borderStrong},
  selected: {backgroundColor: palette.accentSoft},
});
