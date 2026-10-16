import React from 'react';
import {Pressable, View} from 'react-native';
import {TABS, type TabName} from '../screens/tabs';
import {usePalette, useStyles} from '../theme/ThemeProvider';
import type {Palette} from '../theme/tokens';
import {Icon} from './ui/Icon';
import {Txt} from './ui/Txt';

export const NAVIGATION_BAR_HEIGHT = 80;

/** Material 3 navigation bar: four destinations, active pill behind the icon. */
export function NavigationBar({active, onChange, bottomInset, badges}: {active: TabName; onChange: (tab: TabName) => void; bottomInset: number; badges: Partial<Record<TabName, boolean>>}) {
  const palette = usePalette();
  const styles = useStyles(makeStyles);
  return (
    <View style={[styles.bar, {paddingBottom: bottomInset}]} accessibilityRole="tablist">
      {TABS.map(tab => {
        const selected = tab.name === active;
        return (
          <Pressable
            key={tab.name}
            accessibilityRole="tab"
            accessibilityLabel={tab.label}
            accessibilityState={{selected}}
            onPress={() => onChange(tab.name)}
            style={styles.item}>
            {/* collapsable={false}: a flattened pill re-materialized on selection
                loses its rounded corners on Android Fabric. */}
            <View collapsable={false} style={[styles.pill, selected ? styles.pillActive : null]}>
              <Icon name={tab.icon} size={22} color={selected ? palette.accentText : palette.textSecondary} strokeWidth={selected ? 2.2 : 2} />
              {badges[tab.name] ? <View style={styles.badge} /> : null}
            </View>
            <Txt variant="caption" tone={selected ? 'primary' : 'secondary'} style={selected ? styles.labelActive : styles.label} numberOfLines={1}>
              {tab.label}
            </Txt>
          </Pressable>
        );
      })}
    </View>
  );
}

const makeStyles = (palette: Palette) => ({
  bar: {
    flexDirection: 'row' as const,
    backgroundColor: palette.surface,
    borderTopWidth: 1,
    borderTopColor: palette.border,
  },
  item: {flex: 1, height: NAVIGATION_BAR_HEIGHT, alignItems: 'center' as const, justifyContent: 'center' as const, gap: 4},
  // Always painted (transparent when idle) so Android keeps the rounded
  // background drawable when the tab becomes active.
  pill: {width: 64, height: 32, borderRadius: 16, backgroundColor: 'transparent', alignItems: 'center' as const, justifyContent: 'center' as const},
  pillActive: {backgroundColor: palette.accentSoft},
  badge: {
    position: 'absolute' as const,
    top: 3,
    right: 16,
    width: 8,
    height: 8,
    borderRadius: 4,
    backgroundColor: palette.accent,
    borderWidth: 1.5,
    borderColor: palette.surface,
  },
  label: {fontWeight: '500' as const},
  labelActive: {fontWeight: '700' as const},
});
