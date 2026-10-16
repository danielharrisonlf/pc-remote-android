import React, {useMemo, useRef, type ReactNode} from 'react';
import {Animated, PanResponder, View, type GestureResponderEvent, type PanResponderGestureState} from 'react-native';
import Svg, {Circle, Defs, Pattern, Rect} from 'react-native-svg';
import {remote} from '../communication/remote';
import {usePalette, useStyles} from '../theme/ThemeProvider';
import {radius, type Palette} from '../theme/tokens';
import {Icon} from './ui/Icon';

/** Finger travel (dp) per wheel step for two-finger scrolling and the side strip. */
const PAD_SCROLL_STEP = 22;
const STRIP_SCROLL_STEP = 16;
/** Pointer acceleration: slow drags stay precise, fast flicks cross the screen. */
const BASE_GAIN = 1.4;
const ACCELERATION = 2.2;
const MAX_EXTRA_GAIN = 2.6;
/** A touch shorter and smaller than this is a tap, not a drag. */
const TAP_MS = 260;
const TAP_TRAVEL = 10;
const INDICATOR = 64;
const STRIP_WIDTH = 52;

type TouchpadProps = {
  enabled: boolean;
  onTap: (button: 'left' | 'right') => void;
  onScrollTap: (steps: number) => void;
  haptic: () => void;
  /** Shown above the surface when the pad cannot be used yet. */
  overlay?: ReactNode;
  hint?: ReactNode;
};

type GestureState = {lastX: number; lastY: number; touches: number; maxTouches: number; travel: number; startedAt: number; scrollRemainder: number};

const freshGesture = (): GestureState => ({lastX: 0, lastY: 0, touches: 1, maxTouches: 1, travel: 0, startedAt: 0, scrollRemainder: 0});

/**
 * The pad owns its gestures completely: it never yields the responder to a
 * parent, there is no ScrollView around it, and Android native handlers are
 * blocked while a finger is down. Gesture math lives in refs so the responder
 * is created once and the component does not re-render while dragging.
 */
export function Touchpad({enabled, onTap, onScrollTap, haptic, overlay, hint}: TouchpadProps) {
  const palette = usePalette();
  const styles = useStyles(makeStyles);
  const enabledRef = useRef(enabled);
  const callbacks = useRef({onTap, onScrollTap, haptic});
  const gesture = useRef(freshGesture());
  const strip = useRef({startedAt: 0, startY: 0, lastY: 0, travel: 0, remainder: 0});
  const stripHeight = useRef(0);
  const origin = useRef({x: 0, y: 0});
  const surface = useRef<React.ComponentRef<typeof View>>(null);
  const indicator = useRef(new Animated.Value(0)).current;
  const position = useRef(new Animated.ValueXY()).current;
  const stripActive = useRef(new Animated.Value(0)).current;
  enabledRef.current = enabled;
  callbacks.current = {onTap, onScrollTap, haptic};

  const place = (state: PanResponderGestureState) => {
    position.setValue({x: state.moveX - origin.current.x - INDICATOR / 2, y: state.moveY - origin.current.y - INDICATOR / 2});
  };

  const pad = useMemo(
    () =>
      PanResponder.create({
        onStartShouldSetPanResponder: () => enabledRef.current,
        onMoveShouldSetPanResponder: () => enabledRef.current,
        onPanResponderTerminationRequest: () => false,
        onShouldBlockNativeResponder: () => true,
        onPanResponderGrant: (event: GestureResponderEvent, state) => {
          const g = gesture.current;
          Object.assign(g, freshGesture(), {touches: state.numberActiveTouches, maxTouches: state.numberActiveTouches, startedAt: Date.now()});
          position.setValue({x: event.nativeEvent.pageX - origin.current.x - INDICATOR / 2, y: event.nativeEvent.pageY - origin.current.y - INDICATOR / 2});
          Animated.timing(indicator, {toValue: 1, duration: 110, useNativeDriver: true}).start();
        },
        onPanResponderMove: (_event, state) => {
          const g = gesture.current;
          const touches = state.numberActiveTouches;
          // The centroid jumps when a finger lands or lifts; re-anchor instead of moving.
          if (touches !== g.touches) {
            g.touches = touches;
            g.maxTouches = Math.max(g.maxTouches, touches);
            g.lastX = state.dx;
            g.lastY = state.dy;
            return;
          }
          const dx = state.dx - g.lastX;
          const dy = state.dy - g.lastY;
          g.lastX = state.dx;
          g.lastY = state.dy;
          g.travel += Math.abs(dx) + Math.abs(dy);
          place(state);
          if (touches >= 2) {
            // Natural direction, like the phone itself: fingers down reveal content above.
            g.scrollRemainder += dy;
            const steps = Math.trunc(g.scrollRemainder / PAD_SCROLL_STEP);
            if (steps) {
              g.scrollRemainder -= steps * PAD_SCROLL_STEP;
              remote.scroll(steps);
            }
            return;
          }
          const speed = Math.hypot(state.vx, state.vy);
          const gain = BASE_GAIN + Math.min(MAX_EXTRA_GAIN, speed * ACCELERATION);
          remote.movePointer(dx * gain, dy * gain);
        },
        onPanResponderRelease: () => {
          const g = gesture.current;
          remote.cancelPointerMotion();
          Animated.timing(indicator, {toValue: 0, duration: 180, useNativeDriver: true}).start();
          if (Date.now() - g.startedAt < TAP_MS && g.travel < TAP_TRAVEL) {
            callbacks.current.haptic();
            callbacks.current.onTap(g.maxTouches >= 2 ? 'right' : 'left');
          }
        },
        onPanResponderTerminate: () => {
          remote.cancelPointerMotion();
          Animated.timing(indicator, {toValue: 0, duration: 180, useNativeDriver: true}).start();
        },
      }),
    // Refs carry every changing value; the responder must stay stable mid-gesture.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  );

  const scrollStrip = useMemo(
    () =>
      PanResponder.create({
        onStartShouldSetPanResponder: () => enabledRef.current,
        onMoveShouldSetPanResponder: () => enabledRef.current,
        onPanResponderTerminationRequest: () => false,
        onShouldBlockNativeResponder: () => true,
        onPanResponderGrant: (event: GestureResponderEvent) => {
          strip.current = {startedAt: Date.now(), startY: event.nativeEvent.locationY, lastY: 0, travel: 0, remainder: 0};
          Animated.timing(stripActive, {toValue: 1, duration: 100, useNativeDriver: true}).start();
        },
        onPanResponderMove: (_event, state) => {
          const s = strip.current;
          const dy = state.dy - s.lastY;
          s.lastY = state.dy;
          s.travel += Math.abs(dy);
          // Scrollbar direction: dragging the strip down scrolls the page down.
          s.remainder += dy;
          const steps = Math.trunc(s.remainder / STRIP_SCROLL_STEP);
          if (steps) {
            s.remainder -= steps * STRIP_SCROLL_STEP;
            remote.scroll(-steps);
          }
        },
        onPanResponderRelease: () => {
          const s = strip.current;
          Animated.timing(stripActive, {toValue: 0, duration: 160, useNativeDriver: true}).start();
          if (Date.now() - s.startedAt < TAP_MS && s.travel < TAP_TRAVEL) {
            // Tap the upper half to scroll up, the lower half to scroll down.
            callbacks.current.haptic();
            callbacks.current.onScrollTap(s.startY < stripHeight.current / 2 ? 3 : -3);
          }
        },
        onPanResponderTerminate: () => {
          Animated.timing(stripActive, {toValue: 0, duration: 160, useNativeDriver: true}).start();
        },
      }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  );

  return (
    <View
      ref={surface}
      style={styles.surface}
      onLayout={() => surface.current?.measureInWindow((x, y) => (origin.current = {x, y}))}
      accessible
      accessibilityLabel="Touchpad. Um dedo move o cursor, toque clica, dois dedos rolam e toque com dois dedos abre o menu."
      accessibilityState={{disabled: !enabled}}>
      <Svg style={styles.fill} pointerEvents="none">
        <Defs>
          <Pattern id="pad-dots" width={22} height={22} patternUnits="userSpaceOnUse">
            <Circle cx={11} cy={11} r={1.3} fill={palette.padDot} />
          </Pattern>
        </Defs>
        <Rect width="100%" height="100%" fill="url(#pad-dots)" />
      </Svg>
      <View style={styles.area} {...pad.panHandlers}>
        {hint && enabled ? (
          <View style={styles.hint} pointerEvents="none">
            <View style={styles.hintBox}>{hint}</View>
          </View>
        ) : null}
        <Animated.View
          pointerEvents="none"
          style={[styles.indicator, {opacity: indicator, transform: [...position.getTranslateTransform(), {scale: indicator.interpolate({inputRange: [0, 1], outputRange: [0.6, 1]})}]}]}
        />
      </View>
      <View style={styles.strip} onLayout={event => (stripHeight.current = event.nativeEvent.layout.height)} {...scrollStrip.panHandlers}>
        <Animated.View pointerEvents="none" style={[styles.stripActive, {opacity: stripActive}]} />
        <View pointerEvents="none" style={styles.stripIcons}>
          <Icon name="chevron-up" size={18} color={palette.textTertiary} />
          <View style={styles.grip}>
            <View style={styles.gripLine} />
            <View style={styles.gripLine} />
            <View style={styles.gripLine} />
          </View>
          <Icon name="chevron-down" size={18} color={palette.textTertiary} />
        </View>
      </View>
      {overlay ? <View style={styles.overlay}>{overlay}</View> : null}
    </View>
  );
}

const makeStyles = (palette: Palette) => ({
  surface: {
    flex: 1,
    flexDirection: 'row' as const,
    borderRadius: radius.xxl,
    backgroundColor: palette.pad,
    borderWidth: 1,
    borderColor: palette.border,
    overflow: 'hidden' as const,
  },
  fill: {position: 'absolute' as const, top: 0, left: 0, right: 0, bottom: 0},
  area: {flex: 1},
  hint: {position: 'absolute' as const, left: 0, right: 0, bottom: 16, alignItems: 'center' as const},
  hintBox: {alignItems: 'center' as const, gap: 2, paddingHorizontal: 12, paddingVertical: 6, borderRadius: radius.md, backgroundColor: palette.pad},
  indicator: {
    position: 'absolute' as const,
    top: 0,
    left: 0,
    width: INDICATOR,
    height: INDICATOR,
    borderRadius: INDICATOR / 2,
    backgroundColor: palette.ripple,
    borderWidth: 1.5,
    borderColor: palette.accent,
  },
  strip: {width: STRIP_WIDTH, borderLeftWidth: 1, borderLeftColor: palette.border, backgroundColor: palette.surfaceSunken},
  stripActive: {position: 'absolute' as const, top: 0, left: 0, right: 0, bottom: 0, backgroundColor: palette.accentSoft},
  stripIcons: {flex: 1, alignItems: 'center' as const, justifyContent: 'space-between' as const, paddingVertical: 14},
  grip: {gap: 4, alignItems: 'center' as const},
  gripLine: {width: 16, height: 2, borderRadius: 1, backgroundColor: palette.borderStrong},
  overlay: {
    position: 'absolute' as const,
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    backgroundColor: palette.pad,
    alignItems: 'center' as const,
    justifyContent: 'center' as const,
    padding: 24,
  },
});
