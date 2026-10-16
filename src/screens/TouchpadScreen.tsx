import React, {useCallback, useState} from 'react';
import {Pressable, ScrollView, View} from 'react-native';
import {remote} from '../communication/remote';
import {Touchpad} from '../components/Touchpad';
import {Button, IconButton} from '../components/ui/Button';
import {EmptyState, StatusChip} from '../components/ui/Bits';
import {Icon, type IconName} from '../components/ui/Icon';
import {useSnackbar} from '../components/ui/Snackbar';
import {Txt} from '../components/ui/Txt';
import type {Action} from '../domain/protocol';
import {useHaptics, useRemoteValue} from '../hooks/useRemote';
import {usePalette, useStyles} from '../theme/ThemeProvider';
import {radius, space, type Palette} from '../theme/tokens';
import {KeyboardSheet} from './KeyboardSheet';
import type {TabName} from './tabs';

type DiscreteAction = Extract<Action, {kind: 'pointer.click' | 'keyboard.key' | 'keyboard.text'}>;
type QuickKey = {label: string; icon?: IconName; action: DiscreteAction; a11y?: string};

const QUICK_KEYS: QuickKey[] = [
  {label: 'Esc', action: {kind: 'keyboard.key', key: 'escape'}},
  {label: 'Tab', action: {kind: 'keyboard.key', key: 'tab'}},
  {label: 'Enter', icon: 'corner-down-left', action: {kind: 'keyboard.key', key: 'enter'}},
  {label: 'Espaço', icon: 'space', action: {kind: 'keyboard.text', text: ' '}},
  {label: '', icon: 'delete', action: {kind: 'keyboard.key', key: 'backspace'}, a11y: 'Apagar'},
  {label: '', icon: 'arrow-left', action: {kind: 'keyboard.key', key: 'left'}, a11y: 'Seta para a esquerda'},
  {label: '', icon: 'arrow-up', action: {kind: 'keyboard.key', key: 'up'}, a11y: 'Seta para cima'},
  {label: '', icon: 'arrow-down', action: {kind: 'keyboard.key', key: 'down'}, a11y: 'Seta para baixo'},
  {label: '', icon: 'arrow-right', action: {kind: 'keyboard.key', key: 'right'}, a11y: 'Seta para a direita'},
  {label: 'Del', action: {kind: 'keyboard.key', key: 'delete'}, a11y: 'Delete'},
  {label: 'Home', action: {kind: 'keyboard.key', key: 'home'}},
  {label: 'End', action: {kind: 'keyboard.key', key: 'end'}},
  {label: 'Duplo clique', icon: 'mouse-pointer-click', action: {kind: 'pointer.click', button: 'left', count: 2}},
];

/**
 * Focus mode for remote input: a full-height pad with no page scroll, the
 * click buttons in the thumb zone and the frequent keys right above them.
 */
export function TouchpadScreen({onNavigate}: {onNavigate: (tab: TabName) => void}) {
  const palette = usePalette();
  const styles = useStyles(makeStyles);
  const snackbar = useSnackbar();
  const haptic = useHaptics();
  const [keyboardOpen, setKeyboardOpen] = useState(false);
  const connected = useRemoteValue(view => view.status === 'conectado');
  const feature = useRemoteValue(view => view.inputFeature);
  const allowed = useRemoteValue(view => view.state?.input?.allowed ?? null);
  const activating = useRemoteValue(view => view.inputActivating);
  const enabled = useRemoteValue(
    view => view.status === 'conectado' && view.foreground && view.inputArmed && view.state?.input?.allowed === true && view.state.input.enabled === true,
  );

  const perform = useCallback(
    (action: DiscreteAction) => {
      remote.inputAction(action).catch(error => snackbar.show(String((error as Error).message), 'error'));
    },
    [snackbar],
  );

  const activate = () => {
    remote
      .enableInput()
      .then(() => haptic('confirm'))
      .catch(error => snackbar.show(String((error as Error).message), 'error'));
  };

  let overlay: React.ReactNode = null;
  if (!connected) {
    overlay = (
      <EmptyState icon="unplug" title="Sem conexão com o PC" action={<Button label="Abrir Conexão" variant="tonal" onPress={() => onNavigate('connection')} />}>
        Conecte-se a um computador pareado para usar o touchpad.
      </EmptyState>
    );
  } else if (!feature) {
    overlay = (
      <EmptyState icon="circle-arrow-up" title="Atualize o PC Remote no Windows">
        A versão instalada no computador ainda não aceita mouse e teclado.
      </EmptyState>
    );
  } else if (allowed === false) {
    overlay = (
      <EmptyState icon="lock" title="Bloqueado no PC">
        No Windows, abra Transferência e ligue “Permitir mouse e teclado pelo celular”.
      </EmptyState>
    );
  } else if (!enabled) {
    overlay = (
      <EmptyState icon="touchpad" title="Touchpad desligado" action={<Button label="Ativar touchpad" size="lg" loading={activating} onPress={activate} />}>
        Os cliques e o texto vão para a janela que está em foco no Windows.
      </EmptyState>
    );
  }

  return (
    <View style={styles.screen}>
      <View style={styles.toolbar}>
        <View style={styles.chip}>
          <StatusChip tone={enabled ? 'success' : 'neutral'} label={enabled ? 'Controle ativo' : 'Controle desligado'} />
        </View>
        {enabled ? <Button label="Desligar" variant="text" icon="hand" onPress={() => remote.disableInput()} /> : null}
      </View>

      <Touchpad
        enabled={enabled}
        haptic={() => haptic('tap')}
        onTap={button => perform({kind: 'pointer.click', button, count: 1})}
        onScrollTap={steps => remote.scroll(steps)}
        overlay={overlay}
        hint={
          <>
            <Txt variant="caption" tone="tertiary">
              Um dedo move o cursor, um toque clica
            </Txt>
            <Txt variant="caption" tone="tertiary">
              Dois dedos rolam, toque com dois dedos abre o menu
            </Txt>
          </>
        }
      />

      <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.keys} style={styles.keysRow} keyboardShouldPersistTaps="handled">
        {QUICK_KEYS.map(key => (
          <Pressable
            key={key.a11y ?? key.label}
            accessibilityRole="button"
            accessibilityLabel={key.a11y ?? key.label}
            disabled={!enabled}
            onPress={() => {
              haptic('tap');
              perform(key.action);
            }}
            android_ripple={{color: palette.ripple}}
            style={({pressed}) => [styles.key, !enabled ? styles.disabled : null, pressed ? styles.keyPressed : null]}>
            {key.icon ? <Icon name={key.icon} size={18} color={palette.text} /> : null}
            {key.label ? <Txt variant="label">{key.label}</Txt> : null}
          </Pressable>
        ))}
      </ScrollView>

      <View style={styles.clicks}>
        <ClickButton label="Clique esquerdo" disabled={!enabled} onPress={() => perform({kind: 'pointer.click', button: 'left', count: 1})} haptic={haptic} />
        <IconButton icon="keyboard" label="Abrir teclado" variant="tonal" size={64} disabled={!enabled} onPress={() => setKeyboardOpen(true)} />
        <ClickButton label="Clique direito" disabled={!enabled} onPress={() => perform({kind: 'pointer.click', button: 'right', count: 1})} haptic={haptic} />
      </View>

      <KeyboardSheet visible={keyboardOpen} onClose={() => setKeyboardOpen(false)} />
    </View>
  );
}

function ClickButton({label, disabled, onPress, haptic}: {label: string; disabled: boolean; onPress: () => void; haptic: (kind?: 'tap') => void}) {
  const palette = usePalette();
  const styles = useStyles(makeStyles);
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{disabled}}
      disabled={disabled}
      onPressIn={() => haptic('tap')}
      onPress={onPress}
      android_ripple={{color: palette.ripple}}
      style={({pressed}) => [styles.click, pressed ? styles.clickPressed : null, disabled ? styles.disabled : null]}>
      <Txt variant="label" tone="secondary">
        {label}
      </Txt>
    </Pressable>
  );
}

const makeStyles = (palette: Palette) => ({
  screen: {flex: 1, paddingHorizontal: space.lg, paddingTop: space.sm, paddingBottom: space.md, gap: space.md},
  chip: {justifyContent: 'center' as const},
  toolbar: {flexDirection: 'row' as const, alignItems: 'center' as const, justifyContent: 'space-between' as const, minHeight: 48},
  keysRow: {flexGrow: 0},
  keys: {gap: space.sm, paddingVertical: 2},
  key: {
    minWidth: 52,
    height: 44,
    paddingHorizontal: 14,
    borderRadius: radius.md,
    backgroundColor: palette.surface,
    borderWidth: 1,
    borderColor: palette.border,
    flexDirection: 'row' as const,
    alignItems: 'center' as const,
    justifyContent: 'center' as const,
    gap: 6,
    overflow: 'hidden' as const,
  },
  keyPressed: {backgroundColor: palette.accentSoft},
  disabled: {opacity: 0.4},
  clicks: {flexDirection: 'row' as const, alignItems: 'center' as const, gap: space.sm},
  click: {
    flex: 1,
    height: 64,
    borderRadius: radius.xl,
    backgroundColor: palette.surface,
    borderWidth: 1,
    borderColor: palette.border,
    alignItems: 'center' as const,
    justifyContent: 'center' as const,
    overflow: 'hidden' as const,
  },
  clickPressed: {backgroundColor: palette.accentSoft, borderColor: palette.accent},
});
