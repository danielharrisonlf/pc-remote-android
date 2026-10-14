import React, {useCallback, useEffect, useState, useSyncExternalStore} from 'react';
import {Alert, BackHandler, KeyboardAvoidingView, PermissionsAndroid, Platform, Pressable, ScrollView, StatusBar, Text, useColorScheme, Vibration, View} from 'react-native';
import Slider from '@react-native-community/slider';
import {SafeAreaProvider, SafeAreaView} from 'react-native-safe-area-context';
import NativePCRemote from '../integrations/NativePCRemote';
import {remote} from '../communication/remote';
import type {Action} from '../domain/protocol';
import {Button, Card, Field, Setting} from '../components/Controls';
import {SessionControls} from '../components/SessionControls';
import {InputControls} from '../components/InputControls';
import {Pairing} from './PairingScreen';
import {light, dark, styles} from '../theme/theme';

export default function ControlScreen() {
  const view = useSyncExternalStore(remote.subscribe, remote.getSnapshot);
  const scheme = useColorScheme();
  const palette = scheme === 'dark' ? dark : light;
  const [pairing, setPairing] = useState(false);
  const [settings, setSettings] = useState(false);
  const [addressHost, setAddressHost] = useState('');
  const [addressPort, setAddressPort] = useState('43127');
  const [feedback, setFeedback] = useState('');
  const haptics = view.haptics;
  const connected = view.status === 'conectado';
  useEffect(() => {remote.start();}, []);
  useEffect(() => {if (settings) {NativePCRemote.getSavedComputer().then(raw => {if (raw) {const saved = JSON.parse(raw) as {host: string; port: number}; setAddressHost(saved.host); setAddressPort(String(saved.port));}}).catch(() => undefined);}}, [settings]);
  useEffect(() => {const sub = BackHandler.addEventListener('hardwareBackPress', () => {if (pairing) {setPairing(false); return true;} if (settings) {setSettings(false); return true;} return false;}); return () => sub.remove();}, [pairing, settings]);
  useEffect(() => {if (feedback) {const timer = setTimeout(() => setFeedback(''), 3000); return () => clearTimeout(timer);}}, [feedback]);
  const confirmed = useCallback(() => {setFeedback('Ação confirmada pelo PC.'); if (haptics) {Vibration.vibrate(18);}}, [haptics]);
  const action = (a: Action) => {remote.request(a).then(confirmed).catch(error => Alert.alert('Ação indisponível', String(error.message)));};
  const asyncAction = (task: () => Promise<void>) => {task().then(confirmed).catch(error => Alert.alert('Não foi possível concluir', String(error.message)));};
  const volume = view.draggingVolume ?? view.state?.audio.percent ?? 0;
  const audio = view.state?.audio;
  const sessions = view.state?.media.sessions ?? [];
  const session = sessions.find(s => s.id === view.selectedSessionId);
  const toggleDownload = async (enabled: boolean) => {
    if (enabled && Platform.OS === 'android' && Number(Platform.Version) >= 33) {
      await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS, {title: 'Aviso de texto recebido', message: 'Permita avisos para abrir textos recebidos quando o aplicativo estiver em segundo plano.', buttonPositive: 'Permitir'});
    }
    remote.setSync({download: enabled});
  };
  return <SafeAreaProvider><SafeAreaView style={[styles.page, {backgroundColor: palette.bg}]} edges={['top', 'left', 'right', 'bottom']}><StatusBar barStyle={scheme === 'dark' ? 'light-content' : 'dark-content'} />
    <KeyboardAvoidingView style={{flex: 1}} behavior={Platform.OS === 'ios' ? 'padding' : undefined}><ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={styles.content}>
      <View style={styles.header}><View style={{flex: 1}}><Text style={[styles.eyebrow, {color: palette.accent}]}>PC REMOTE</Text><Text style={[styles.heading, {color: palette.ink}]}>{view.pcName || 'Seu computador'}</Text><Text accessibilityRole="text" style={[styles.status, {color: connected ? palette.good : palette.warn}]}>{connected ? '●' : '○'} {view.status}</Text></View><Pressable accessibilityRole="button" accessibilityLabel="Configurações" onPress={() => setSettings(!settings)} style={[styles.iconButton, {backgroundColor: palette.surface}]}><Text style={{fontSize: 22, color: palette.ink}}>⚙</Text></Pressable></View>
      {view.error ? <Text style={[styles.notice, {color: palette.warn, backgroundColor: palette.surface}]}>{view.error}</Text> : null}
      {feedback ? <Text accessibilityLiveRegion="polite" style={[styles.notice, {color: palette.good, backgroundColor: palette.surface}]}>{feedback}</Text> : null}
      {!view.pcName || pairing ? <Pairing palette={palette} onClose={() => setPairing(false)} /> : null}
      <Card title="Volume do PC" palette={palette}><View style={styles.volumeHeader}><Text style={[styles.volumeValue, {color: palette.ink}]}>{volume}%</Text><Text style={[styles.body, {color: palette.soft}]}>{audio?.available ? (audio.muted ? 'Sem som' : 'Saída padrão') : 'Dispositivo de áudio indisponível'}</Text></View>
        <Slider accessibilityLabel="Volume principal" minimumValue={0} maximumValue={100} step={1} value={volume} minimumTrackTintColor={palette.accent} maximumTrackTintColor={palette.line} thumbTintColor={palette.accent} disabled={!connected || !audio?.available}
          onSlidingStart={remote.beginVolume.bind(remote)} onValueChange={remote.updateVolume.bind(remote)} onSlidingComplete={remote.endVolume.bind(remote)} style={styles.slider} />
        <View style={styles.row}><Button label="−5%" onPress={() => action({kind: 'volume.step', delta: -5})} disabled={!connected || !audio?.available} secondary palette={palette} /><Button label={audio?.muted ? 'Ativar som' : 'Mudo'} onPress={() => action({kind: 'volume.mute', muted: !audio?.muted})} disabled={!connected || !audio?.available} secondary={!audio?.muted} palette={palette} /><Button label="+5%" onPress={() => action({kind: 'volume.step', delta: 5})} disabled={!connected || !audio?.available} secondary palette={palette} /></View>
      </Card>
      <Card title="Mídia" palette={palette}>{sessions.length > 1 ? <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{gap: 8, paddingBottom: 12}}>{sessions.map(s => <Pressable key={s.id} accessibilityRole="button" accessibilityState={{selected: s.id === view.selectedSessionId}} onPress={() => remote.selectSession(s.id)} style={[styles.choice, {borderColor: s.id === view.selectedSessionId ? palette.accent : palette.line, backgroundColor: s.id === view.selectedSessionId ? palette.accentPale : palette.surface}]}><Text style={{color: palette.ink}} numberOfLines={1}>{s.app || s.title}</Text></Pressable>)}</ScrollView> : null}<SessionControls session={session} palette={palette} action={action} connected={connected} /></Card>
      <Card title="Área de transferência" palette={palette}><Text style={[styles.body, {color: palette.soft}]}>Texto recebido do PC</Text><Text selectable style={[styles.clipText, {color: palette.ink, backgroundColor: palette.bg}]} numberOfLines={5}>{view.receivedText || 'Nenhum texto recebido nesta sessão.'}</Text>
        <Text style={[styles.hint, {color: palette.soft}]}>{view.paused ? 'Sincronização pausada' : `Celular → PC: ${view.upload ? 'ligado' : 'desligado'} · PC → celular: ${view.download ? 'ligado' : 'desligado'}`}</Text>
        <View style={styles.row}><Button label={view.paused ? 'Retomar sincronização' : 'Pausar sincronização'} onPress={() => remote.setSync({paused: !view.paused})} secondary palette={palette} /></View>
        <View style={styles.row}><Button label="Enviar para o PC" onPress={() => asyncAction(() => remote.sendClipboard())} disabled={!connected} palette={palette} /><Button label="Copiar do PC" onPress={() => asyncAction(() => remote.getClipboard())} disabled={!connected} secondary palette={palette} /></View>
        {view.sharedText ? <Text style={[styles.body, {color: palette.good}]}>Texto recebido do menu Compartilhar. Toque em Enviar para o PC.</Text> : null}
        <View style={styles.row}><Button label="Copiar texto recebido" onPress={() => {remote.copyReceived().then(() => {setFeedback('Texto copiado no celular.'); if (haptics) {Vibration.vibrate(18);}}).catch(error => Alert.alert('Área de transferência', String(error.message)));}} disabled={!view.receivedText} secondary palette={palette} /></View>
        <Text style={[styles.hint, {color: palette.soft}]}>Para enviar cópias de outros apps, abra o PC Remote ou use Compartilhar. O Android limita a leitura automática em segundo plano.</Text>
      </Card>
      <InputControls view={view} palette={palette} confirmed={confirmed} />
      {settings ? <Card title="Configurações" palette={palette}>
        <Field label="Endereço do PC pareado" value={addressHost} onChange={setAddressHost} palette={palette} />
        <Field label="Porta" value={addressPort} onChange={setAddressPort} palette={palette} numeric />
        <Button label="Atualizar endereço e reconectar" onPress={() => {NativePCRemote.updateAddress(addressHost.trim(), Number(addressPort)).catch(error => Alert.alert('Conexão', String(error.message)));}} secondary palette={palette} />
        <Text style={[styles.hint, {color: palette.soft}]}>A identidade TLS permanece a mesma. Use o novo IPv4 se a descoberta não encontrar o PC.</Text>
        <Setting label="Celular → PC" detail="Detectar cópias com este app em foco" value={view.upload} onValueChange={enabled => remote.setSync({upload: enabled})} palette={palette} />
        <Setting label="PC → celular" detail="Receber novas cópias do Windows" value={view.download} onValueChange={toggleDownload} palette={palette} />
        <Setting label="Aplicar automaticamente" detail="Somente com o app em foco" value={view.autoApply} onValueChange={enabled => remote.setSync({autoApply: enabled})} palette={palette} />
        <Setting label="Pausar sincronização" detail="Mantém os controles conectados" value={view.paused} onValueChange={enabled => remote.setSync({paused: enabled})} palette={palette} />
        <Setting label="Feedback tátil" detail="Vibração breve após confirmação" value={haptics} onValueChange={enabled => remote.setHaptics(enabled)} palette={palette} />
        <View style={styles.row}><Button label="Parear outro PC" onPress={() => setPairing(true)} secondary palette={palette} /><Button label="Desconectar" onPress={() => remote.disconnect()} secondary palette={palette} /></View>
        <View style={styles.row}><Button label="Remover acesso deste celular" onPress={() => Alert.alert('Remover PC', 'Esta ação apaga a credencial deste celular. Revogue também o dispositivo no PC.', [{text: 'Cancelar'}, {text: 'Remover', style: 'destructive', onPress: () => remote.forget()}])} secondary palette={palette} /></View>
      </Card> : null}
    </ScrollView></KeyboardAvoidingView></SafeAreaView></SafeAreaProvider>;
}
