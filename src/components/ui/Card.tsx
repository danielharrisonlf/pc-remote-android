import React, {type ReactNode} from 'react';
import {View, type StyleProp, type ViewStyle} from 'react-native';
import {useStyles} from '../../theme/ThemeProvider';
import {radius, space, type Palette} from '../../theme/tokens';
import {Txt} from './Txt';

type CardProps = {
  title?: string;
  subtitle?: string;
  trailing?: ReactNode;
  children?: ReactNode;
  tone?: 'default' | 'attention' | 'sunken';
  style?: StyleProp<ViewStyle>;
};

export function Card({title, subtitle, trailing, children, tone = 'default', style}: CardProps) {
  const styles = useStyles(makeStyles);
  return (
    <View style={[styles.card, tone === 'attention' ? styles.attention : tone === 'sunken' ? styles.sunken : null, style]}>
      {title ? (
        <View style={styles.header}>
          <View style={styles.headerText}>
            <Txt variant="title" accessibilityRole="header">
              {title}
            </Txt>
            {subtitle ? (
              <Txt variant="caption" tone="secondary" style={styles.subtitle}>
                {subtitle}
              </Txt>
            ) : null}
          </View>
          {trailing}
        </View>
      ) : null}
      {children}
    </View>
  );
}

const makeStyles = (palette: Palette) => ({
  card: {
    backgroundColor: palette.surface,
    borderColor: palette.border,
    borderWidth: 1,
    borderRadius: radius.xl,
    padding: space.xl,
    gap: space.lg,
  },
  attention: {backgroundColor: palette.warningSoft, borderColor: palette.warning},
  sunken: {backgroundColor: palette.surfaceSunken},
  header: {flexDirection: 'row' as const, alignItems: 'flex-start' as const, gap: space.md},
  headerText: {flex: 1, gap: 2},
  subtitle: {fontSize: 13, lineHeight: 18},
});
