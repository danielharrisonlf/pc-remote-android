import React, {createContext, useContext, useEffect, useMemo, useRef, useState, type ReactNode} from 'react';
import {AccessibilityInfo, Animated, StyleSheet, useColorScheme, type ImageStyle, type TextStyle, type ViewStyle} from 'react-native';
import type {ThemePreference} from '../communication/remote';
import {darkPalette, lightPalette, type Palette} from './tokens';

const ThemeContext = createContext<Palette>(lightPalette);

/**
 * Resolves the preference against the OS scheme. A theme change briefly fades
 * from the previous background color so the switch reads as one smooth step.
 */
export function ThemeProvider({preference, children}: {preference: ThemePreference; children: ReactNode}) {
  const system = useColorScheme();
  const dark = preference === 'dark' || (preference === 'system' && system === 'dark');
  const palette = dark ? darkPalette : lightPalette;
  const previous = useRef(palette);
  const [veil, setVeil] = useState<string | null>(null);
  const opacity = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    if (previous.current === palette) {
      return;
    }
    const from = previous.current.bg;
    previous.current = palette;
    let cancelled = false;
    AccessibilityInfo.isReduceMotionEnabled().then(reduce => {
      if (cancelled || reduce) {
        return;
      }
      setVeil(from);
      opacity.setValue(1);
      Animated.timing(opacity, {toValue: 0, duration: 220, useNativeDriver: true}).start(() => setVeil(null));
    });
    return () => {
      cancelled = true;
    };
  }, [palette, opacity]);

  return (
    <ThemeContext.Provider value={palette}>
      {children}
      {veil ? <Animated.View pointerEvents="none" style={[StyleSheet.absoluteFill, {backgroundColor: veil, opacity}]} /> : null}
    </ThemeContext.Provider>
  );
}

export function usePalette(): Palette {
  return useContext(ThemeContext);
}

/** Theme-aware StyleSheet, rebuilt only when the palette changes. */
export function useStyles<S extends Record<string, ViewStyle | TextStyle | ImageStyle>>(factory: (palette: Palette) => S): Readonly<S> {
  const palette = usePalette();
  // The factory is a module-level function in every caller.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  return useMemo(() => StyleSheet.create(factory(palette)), [palette]);
}
