import React, {useEffect, useState} from 'react';
import {Alert, NativeEventEmitter, Pressable, Text, TextInput, View} from 'react-native';
import NativePCRemote from '../integrations/NativePCRemote';
import {remote} from '../communication/remote';
import {Button, Card} from '../components/Controls';
import {styles, type Palette} from '../theme/theme';

export function Pairing({palette, onClose}: {palette: Palette; onClose: () => void}) {
  const [host, setHost] = useState('');
  const [port, setPort] = useState('43127');
  const [fingerprint, setFingerprint] = useState('');
  const [code, setCode] = useState('');
  const [found, setFound] = useState<Array<{name: string; host: string; port: number}>>([]);
  useEffect(() => {
    const subscription = new NativeEventEmitter(NativePCRemote).addListener('remote.discovery', raw => {
      try {const item = JSON.parse(raw as string); if (item.host && item.port) {setFound(previous => previous.some(p => p.host === item.host) ? previous : [...previous, item]);}} catch {}
    });
    NativePCRemote.startDiscovery();
    return () => {subscription.remove(); NativePCRemote.stopDiscovery();};
  }, []);
  const scan = async () => {
    try {
      const invite = JSON.parse(await NativePCRemote.scanQr());
      if (invite.v !== 1 || typeof invite.host !== 'string' || typeof invite.fingerprint !== 'string' || typeof invite.code !== 'string') {throw new Error('QR incompatível.');}
      setHost(invite.host); setPort(String(invite.port)); setFingerprint(invite.fingerprint); setCode(invite.code);
    } catch (error) {Alert.alert('Pareamento', String((error as Error).message));}
  };
  const connect = () => {
    if (!host.trim() || !/^[0-9]{8}$/.test(code.trim()) || !/^[a-fA-F0-9:]{64,95}$/.test(fingerprint.trim()) || Number(port) < 1 || Number(port) > 65535) {
      Alert.alert('Confira os dados', 'Informe endereço, porta, código de 8 dígitos e fingerprint exibidos no PC.'); return;
    }
    remote.connect(host, Number(port), fingerprint, code); onClose();
  };
  return <Card title="Parear computador" palette={palette}>
    <Text style={[styles.body, {color: palette.soft}]}>Abra o pareamento no PC. Confirme o pedido na janela do computador. A descoberta da rede só sugere o endereço; confirme código e identidade no PC.</Text>
    <View style={styles.row}><Button label="Ler QR Code" onPress={scan} palette={palette} secondary /></View>
    {found.map(item => <Pressable key={`${item.host}:${item.port}`} onPress={() => {setHost(item.host); setPort(String(item.port));}} style={[styles.choice, {borderColor: palette.line}]}><Text style={{color: palette.ink}}>{item.name} · {item.host}</Text></Pressable>)}
    <TextField label="Endereço IP ou nome" value={host} onChangeText={setHost} palette={palette} autoCapitalize="none" />
    <TextField label="Porta" value={port} onChangeText={setPort} palette={palette} keyboardType="number-pad" />
    <TextField label="Fingerprint SHA-256 do PC" value={fingerprint} onChangeText={setFingerprint} palette={palette} autoCapitalize="none" />
    <TextField label="Código temporário" value={code} onChangeText={setCode} palette={palette} keyboardType="number-pad" />
    <View style={styles.row}><Button label="Solicitar pareamento" onPress={connect} palette={palette} /></View>
  </Card>;
}

function TextField({label, value, onChangeText, palette, keyboardType, autoCapitalize}: {label: string; value: string; onChangeText: (text: string) => void; palette: Palette; keyboardType?: 'number-pad'; autoCapitalize?: 'none'}) {
  return <View style={{marginTop: 14}}><Text style={[styles.fieldLabel, {color: palette.soft}]}>{label}</Text><TextInput accessibilityLabel={label} value={value} onChangeText={onChangeText} keyboardType={keyboardType} autoCapitalize={autoCapitalize}
    autoCorrect={false} selectionColor={palette.accent} placeholderTextColor={palette.soft} style={[styles.input, {color: palette.ink, borderColor: palette.line, backgroundColor: palette.bg}]} /></View>;
}
