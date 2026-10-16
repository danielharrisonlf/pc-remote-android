import React, {createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode} from 'react';
import {AccessibilityInfo, Animated, Pressable, View} from 'react-native';
import {usePalette, useStyles} from '../../theme/ThemeProvider';
import {radius, space, type Palette} from '../../theme/tokens';
import {Icon} from './Icon';
import {Txt} from './Txt';

type Tone = 'info' | 'success' | 'error';
type Message = {id: number; text: string; tone: Tone; action?: {label: string; run: () => void}};
type SnackbarApi = {show: (text: string, tone?: Tone, action?: Message['action']) => void};

const SnackbarContext = createContext<SnackbarApi>({show: () => undefined});
const DURATION: Record<Tone, number> = {info: 3200, success: 2600, error: 5000};

/** Transient feedback above the navigation bar; it never moves the content. */
export function SnackbarProvider({children, bottomOffset}: {children: ReactNode; bottomOffset: number}) {
  const [message, setMessage] = useState<Message | null>(null);
  const nextId = useRef(1);
  const show = useCallback((text: string, tone: Tone = 'info', action?: Message['action']) => {
    setMessage({id: nextId.current++, text, tone, action});
    AccessibilityInfo.announceForAccessibility(text);
  }, []);
  const api = useMemo(() => ({show}), [show]);
  return (
    <SnackbarContext.Provider value={api}>
      {children}
      {message ? <SnackbarView key={message.id} message={message} bottomOffset={bottomOffset} onDone={() => setMessage(null)} /> : null}
    </SnackbarContext.Provider>
  );
}

function SnackbarView({message, bottomOffset, onDone}: {message: Message; bottomOffset: number; onDone: () => void}) {
  const palette = usePalette();
  const styles = useStyles(makeStyles);
  const enter = useRef(new Animated.Value(0)).current;
  // The parent re-renders with every remote update; keep the timer tied to the
  // message only, or frequent renders would keep postponing the dismissal.
  const done = useRef(onDone);
  done.current = onDone;

  useEffect(() => {
    Animated.timing(enter, {toValue: 1, duration: 200, useNativeDriver: true}).start();
    const timer = setTimeout(() => {
      Animated.timing(enter, {toValue: 0, duration: 160, useNativeDriver: true}).start(() => done.current());
    }, DURATION[message.tone]);
    return () => clearTimeout(timer);
  }, [enter, message]);

  const iconColor = message.tone === 'error' ? palette.danger : message.tone === 'success' ? palette.success : palette.accentText;
  return (
    <Animated.View
      accessibilityLiveRegion="polite"
      pointerEvents="box-none"
      style={[
        styles.wrap,
        {bottom: bottomOffset + space.md, opacity: enter, transform: [{translateY: enter.interpolate({inputRange: [0, 1], outputRange: [16, 0]})}]},
      ]}>
      <View style={styles.bar}>
        <Icon name={message.tone === 'error' ? 'circle-alert' : message.tone === 'success' ? 'circle-check' : 'info'} size={20} color={iconColor} />
        <Txt variant="body" style={styles.text}>
          {message.text}
        </Txt>
        {message.action ? (
          <Pressable
            accessibilityRole="button"
            onPress={() => {
              message.action?.run();
              onDone();
            }}
            style={styles.action}>
            <Txt variant="label" tone="accent">
              {message.action.label}
            </Txt>
          </Pressable>
        ) : null}
      </View>
    </Animated.View>
  );
}

export function useSnackbar(): SnackbarApi {
  return useContext(SnackbarContext);
}

const makeStyles = (palette: Palette) => ({
  wrap: {position: 'absolute' as const, left: space.lg, right: space.lg},
  bar: {
    flexDirection: 'row' as const,
    alignItems: 'center' as const,
    gap: space.md,
    minHeight: 52,
    paddingVertical: space.md,
    paddingLeft: space.lg,
    paddingRight: space.sm,
    borderRadius: radius.md,
    backgroundColor: palette.surfaceHigh,
    borderWidth: 1,
    borderColor: palette.borderStrong,
    elevation: 6,
    shadowColor: '#000',
    shadowOpacity: 0.18,
    shadowRadius: 12,
    shadowOffset: {width: 0, height: 6},
  },
  text: {flex: 1, fontSize: 14, lineHeight: 20},
  action: {minHeight: 44, paddingHorizontal: space.md, justifyContent: 'center' as const},
});
