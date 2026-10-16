import React, {useEffect, useRef, type ReactNode} from 'react';
import {Animated, KeyboardAvoidingView, Modal, Pressable, ScrollView, View} from 'react-native';
import {useSafeAreaInsets} from 'react-native-safe-area-context';
import {usePalette, useStyles} from '../../theme/ThemeProvider';
import {radius, space, type Palette} from '../../theme/tokens';
import {IconButton} from './Button';
import {Txt} from './Txt';

type SheetProps = {
  visible: boolean;
  onClose: () => void;
  title: string;
  subtitle?: string;
  children: ReactNode;
  footer?: ReactNode;
  /** Scrollable body for long content such as release notes. */
  scroll?: boolean;
};

/**
 * Material bottom sheet on a Modal: system Back and the scrim close it, and the
 * Modal window resizes for the keyboard so inputs stay visible.
 */
export function Sheet({visible, onClose, title, subtitle, children, footer, scroll}: SheetProps) {
  const palette = usePalette();
  const styles = useStyles(makeStyles);
  const insets = useSafeAreaInsets();
  const slide = useRef(new Animated.Value(40)).current;

  useEffect(() => {
    if (!visible) {
      return;
    }
    slide.setValue(40);
    Animated.spring(slide, {toValue: 0, useNativeDriver: true, damping: 22, stiffness: 260, mass: 0.9}).start();
  }, [visible, slide]);

  const body = scroll ? (
    <ScrollView style={styles.scroll} contentContainerStyle={styles.body} keyboardShouldPersistTaps="handled">
      {children}
    </ScrollView>
  ) : (
    <View style={styles.body}>{children}</View>
  );

  return (
    <Modal visible={visible} transparent animationType="fade" statusBarTranslucent navigationBarTranslucent onRequestClose={onClose}>
      {/* An edge-to-edge Modal window is not resized for the IME, so the sheet
          lifts itself by the keyboard overlap. */}
      <KeyboardAvoidingView behavior="padding" style={styles.root}>
        <Pressable accessibilityRole="button" accessibilityLabel="Fechar" style={styles.scrim} onPress={onClose} />
        <Animated.View style={[styles.sheet, {paddingBottom: Math.max(insets.bottom, space.lg), transform: [{translateY: slide}]}]}>
          <View style={styles.handle} />
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
            <IconButton icon="x" label="Fechar" onPress={onClose} size={40} />
          </View>
          {body}
          {footer ? <View style={[styles.footer, {borderTopColor: palette.border}]}>{footer}</View> : null}
        </Animated.View>
      </KeyboardAvoidingView>
    </Modal>
  );
}

const makeStyles = (palette: Palette) => ({
  root: {flex: 1, justifyContent: 'flex-end' as const},
  scrim: {position: 'absolute' as const, top: 0, right: 0, bottom: 0, left: 0, backgroundColor: palette.scrim},
  sheet: {
    maxHeight: '88%' as const,
    backgroundColor: palette.surfaceHigh,
    borderTopLeftRadius: radius.xxl,
    borderTopRightRadius: radius.xxl,
    paddingTop: space.sm,
  },
  handle: {alignSelf: 'center' as const, width: 36, height: 4, borderRadius: 2, backgroundColor: palette.borderStrong, marginBottom: space.sm},
  header: {flexDirection: 'row' as const, alignItems: 'center' as const, gap: space.md, paddingHorizontal: space.xl, paddingBottom: space.sm},
  headerText: {flex: 1, gap: 2},
  subtitle: {fontSize: 13, lineHeight: 18},
  scroll: {flexGrow: 0},
  body: {paddingHorizontal: space.xl, paddingTop: space.sm, paddingBottom: space.lg, gap: space.lg},
  footer: {flexDirection: 'row' as const, gap: space.sm, paddingHorizontal: space.xl, paddingTop: space.md, borderTopWidth: 1},
});
