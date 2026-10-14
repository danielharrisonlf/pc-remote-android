import React, {useCallback, useMemo, useRef, useState} from 'react';
import {Alert, PanResponder, Text, TextInput, View} from 'react-native';
import {remote, type RemoteView} from '../communication/remote';
import {inputTextIsAllowed, type Action} from '../domain/protocol';
import {styles, type Palette} from '../theme/theme';
import {Button, Card} from './Controls';

type KeyAction = Extract<Action, {kind: 'keyboard.key'}>['key'];

type Props = {view: RemoteView; palette: Palette; confirmed: () => void};

function InputControlsContent({view, palette, confirmed}: Props) {
  const [draft, setDraft] = useState('');
  const last = useRef({x: 0, y: 0});
  const enabled = view.status === 'conectado' && view.foreground && view.inputArmed && view.state?.input?.allowed === true && view.state.input.enabled === true;
  const available = view.status === 'conectado' && view.inputFeature && view.state?.input?.allowed === true;
  const perform = useCallback((action: Extract<Action, {kind: 'pointer.click' | 'pointer.scroll' | 'keyboard.key' | 'keyboard.text'}>, clear = false) => {
    remote.inputAction(action).then(() => {if (clear) {setDraft('');} confirmed();}).catch(error => Alert.alert('Controle indisponível', String(error.message)));
  }, [confirmed]);
  const pan = useMemo(() => PanResponder.create({
    onStartShouldSetPanResponder: () => enabled,
    onMoveShouldSetPanResponder: () => enabled,
    onPanResponderGrant: () => {last.current = {x: 0, y: 0};},
    onPanResponderMove: (_event, gesture) => {
      const dx = gesture.dx - last.current.x;
      const dy = gesture.dy - last.current.y;
      last.current = {x: gesture.dx, y: gesture.dy};
      remote.movePointer(dx, dy);
    },
    onPanResponderRelease: (_event, gesture) => {
      remote.cancelPointerMotion();
      if (Math.abs(gesture.dx) < 6 && Math.abs(gesture.dy) < 6) {perform({kind: 'pointer.click', button: 'left', count: 1});}
    },
    onPanResponderTerminate: () => remote.cancelPointerMotion(),
  }), [enabled, perform]);
  const key = (label: string, code: KeyAction) => <Button label={label} palette={palette} secondary disabled={!enabled} onPress={() => perform({kind: 'keyboard.key', key: code})} />;

  return <Card title="Touchpad e teclado" palette={palette}>
    {!view.inputFeature ? <Text style={[styles.hint, {color: palette.soft}]}>Atualize o aplicativo do PC para liberar o controle de entrada.</Text> : null}
    {view.inputFeature && view.state?.input?.allowed === false ? <Text style={[styles.hint, {color: palette.soft}]}>Permita o controle de entrada nas configurações do PC.</Text> : null}
    {view.inputFeature && !enabled ? <Text accessibilityLiveRegion="polite" style={[styles.hint, {color: palette.soft}]}>{view.inputActivating ? 'Aguardando confirmação do PC.' : 'Controle desativado. Ative para mover, clicar ou digitar.'}</Text> : null}
    <Text style={[styles.body, {color: palette.soft}]}>O controle atua no aplicativo que estiver em foco no Windows. Confirme o destino antes de digitar ou clicar.</Text>
    <View style={styles.row}><Button label={view.inputActivating ? 'Ativando…' : enabled ? 'Desativar controle' : 'Ativar controle'} palette={palette} secondary={enabled} disabled={view.inputActivating || (!available && !enabled)} onPress={() => {
      if (enabled) {remote.disableInput();} else {remote.enableInput().catch(error => Alert.alert('Controle indisponível', String(error.message)));}
    }} /></View>
    <View accessible accessibilityRole="adjustable" accessibilityLabel="Área do touchpad. Deslize para mover o cursor; toque para clicar à esquerda." style={{height: 205, backgroundColor: palette.bg, borderColor: palette.line, borderWidth: 1, borderRadius: 18, alignItems: 'center', justifyContent: 'center', opacity: enabled ? 1 : 0.5}} {...pan.panHandlers}>
      <Text style={{color: palette.ink, fontSize: 18, fontWeight: '600'}}>Deslize para mover</Text>
      <Text style={[styles.hint, {color: palette.soft}]}>Toque para clicar</Text>
    </View>
    <View style={styles.row}><Button label="Clique esquerdo" palette={palette} secondary disabled={!enabled} onPress={() => perform({kind: 'pointer.click', button: 'left', count: 1})} /><Button label="Duplo clique" palette={palette} secondary disabled={!enabled} onPress={() => perform({kind: 'pointer.click', button: 'left', count: 2})} /></View>
    <View style={styles.row}><Button label="Clique direito" palette={palette} secondary disabled={!enabled} onPress={() => perform({kind: 'pointer.click', button: 'right', count: 1})} /><Button label="Rolar ↑" palette={palette} secondary disabled={!enabled} onPress={() => perform({kind: 'pointer.scroll', steps: 3})} /><Button label="Rolar ↓" palette={palette} secondary disabled={!enabled} onPress={() => perform({kind: 'pointer.scroll', steps: -3})} /></View>
    <TextInput accessibilityLabel="Texto para digitar no aplicativo em foco no Windows" placeholder="Digite o texto aqui" placeholderTextColor={palette.soft} value={draft} onChangeText={setDraft} multiline={false} autoCorrect={false} maxLength={1024} style={[styles.input, {color: palette.ink, borderColor: palette.line}]} />
    <View style={styles.row}><Button label="Enviar texto" palette={palette} disabled={!enabled || !inputTextIsAllowed(draft)} hint="Texto de 1 a 1024 bytes, sem quebra de linha." onPress={() => perform({kind: 'keyboard.text', text: draft}, true)} /></View>
    <View style={styles.row}>{key('Enter', 'enter')}{key('Apagar', 'backspace')}{key('Tab', 'tab')}{key('Esc', 'escape')}</View>
    <View style={styles.row}>{key('Esquerda', 'left')}{key('Acima', 'up')}{key('Abaixo', 'down')}{key('Direita', 'right')}</View>
    <View style={styles.row}>{key('Del', 'delete')}{key('Home', 'home')}{key('End', 'end')}</View>
  </Card>;
}

export const InputControls = React.memo(InputControlsContent, (a, b) => a.palette === b.palette && a.confirmed === b.confirmed &&
  a.view.status === b.view.status && a.view.foreground === b.view.foreground && a.view.inputFeature === b.view.inputFeature &&
  a.view.inputArmed === b.view.inputArmed && a.view.inputActivating === b.view.inputActivating &&
  a.view.state?.input?.allowed === b.view.state?.input?.allowed && a.view.state?.input?.enabled === b.view.state?.input?.enabled);
