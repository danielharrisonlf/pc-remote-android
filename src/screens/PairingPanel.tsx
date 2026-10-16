import React, {useState} from 'react';
import {ActivityIndicator, Pressable, View} from 'react-native';
import {remote} from '../communication/remote';
import {Button} from '../components/ui/Button';
import {Divider} from '../components/ui/Bits';
import {Icon} from '../components/ui/Icon';
import {useSnackbar} from '../components/ui/Snackbar';
import {TextField} from '../components/ui/TextField';
import {Txt} from '../components/ui/Txt';
import {useDiscovery, useHaptics} from '../hooks/useRemote';
import NativePCRemote from '../integrations/NativePCRemote';
import {usePalette, useStyles} from '../theme/ThemeProvider';
import {radius, space, type Palette} from '../theme/tokens';

type Invite = {host: string; port: number; fingerprint: string; code: string};

function parseInvite(raw: string): Invite {
  const invite = JSON.parse(raw) as {v?: number; host?: unknown; port?: unknown; fingerprint?: unknown; code?: unknown};
  if (invite.v !== 1 || typeof invite.host !== 'string' || typeof invite.fingerprint !== 'string' || typeof invite.code !== 'string' || typeof invite.port !== 'number') {
    throw new Error('Este QR não é um convite do PC Remote.');
  }
  return {host: invite.host, port: invite.port, fingerprint: invite.fingerprint, code: invite.code};
}

function validate(form: {host: string; port: string; code: string; fingerprint: string}) {
  const port = Number(form.port);
  return {
    host: form.host.trim() ? null : 'Informe o endereço mostrado no PC.',
    port: Number.isInteger(port) && port >= 1 && port <= 65535 ? null : 'Porta inválida.',
    code: /^[0-9]{8}$/.test(form.code.replace(/\s/g, '')) ? null : 'O código tem 8 dígitos.',
    fingerprint: /^[a-fA-F0-9:\s]{64,95}$/.test(form.fingerprint.trim()) && form.fingerprint.replace(/[^a-fA-F0-9]/g, '').length === 64 ? null : 'Cole os 64 caracteres da identidade SHA-256.',
  };
}

/**
 * Pairing in three ways: scan the QR (fastest, sends the request right away),
 * pick a PC found on the network, or type the data shown on the PC.
 */
export function PairingPanel({onRequested}: {onRequested?: () => void}) {
  const palette = usePalette();
  const styles = useStyles(makeStyles);
  const snackbar = useSnackbar();
  const haptic = useHaptics();
  const found = useDiscovery(true);
  const [scanning, setScanning] = useState(false);
  const [manual, setManual] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const [form, setForm] = useState({host: '', port: '43127', code: '', fingerprint: ''});
  const errors = validate(form);
  const invalid = Object.values(errors).some(Boolean);

  const request = (invite: Invite) => {
    haptic('confirm');
    remote.connect(invite.host, invite.port, invite.fingerprint, invite.code);
    onRequested?.();
  };

  const scan = async () => {
    setScanning(true);
    try {
      request(parseInvite(await NativePCRemote.scanQr()));
    } catch (error) {
      if ((error as {code?: string}).code !== 'scan_canceled') {
        snackbar.show(String((error as Error).message), 'error');
      }
    } finally {
      setScanning(false);
    }
  };

  const submit = () => {
    setSubmitted(true);
    if (invalid) {
      return;
    }
    request({host: form.host.trim(), port: Number(form.port), fingerprint: form.fingerprint, code: form.code.replace(/\s/g, '')});
  };

  const field = (key: keyof typeof form) => ({
    value: form[key],
    onChangeText: (text: string) => setForm(current => ({...current, [key]: text})),
    error: submitted ? errors[key] : null,
  });

  return (
    <View style={styles.root}>
      <Txt tone="secondary">No Windows, abra o PC Remote e clique em Parear celular. Depois leia o QR Code que aparece na tela.</Txt>
      <Button label="Ler QR Code" icon="scan-qr-code" size="lg" loading={scanning} onPress={scan} />

      <View style={styles.section}>
        <View style={styles.sectionHeader}>
          <Icon name="radar" size={18} color={palette.textSecondary} />
          <Txt variant="label" tone="secondary" style={styles.flex}>
            Nesta rede
          </Txt>
          {found.length === 0 ? <ActivityIndicator size="small" color={palette.textTertiary} /> : null}
        </View>
        {found.length === 0 ? (
          <Txt variant="caption" tone="tertiary">
            Procurando computadores com o PC Remote aberto…
          </Txt>
        ) : (
          found.map(pc => (
            <Pressable
              key={`${pc.host}:${pc.port}`}
              accessibilityRole="button"
              accessibilityLabel={`Usar ${pc.name}, ${pc.host}`}
              onPress={() => {
                setForm(current => ({...current, host: pc.host, port: String(pc.port)}));
                setManual(true);
              }}
              android_ripple={{color: palette.ripple}}
              style={styles.found}>
              <Icon name="monitor" size={20} color={palette.text} />
              <View style={styles.flex}>
                <Txt variant="bodyStrong" numberOfLines={1}>
                  {pc.name}
                </Txt>
                <Txt variant="caption" tone="secondary">
                  {pc.host}:{pc.port}
                </Txt>
              </View>
              <Icon name="arrow-right" size={18} color={palette.textTertiary} />
            </Pressable>
          ))
        )}
        <Txt variant="caption" tone="tertiary">
          A busca só sugere o endereço. O código e a identidade continuam vindo do PC.
        </Txt>
      </View>

      <Divider />
      <Pressable accessibilityRole="button" accessibilityState={{expanded: manual}} onPress={() => setManual(!manual)} style={styles.toggle}>
        <Txt variant="label" style={styles.flex}>
          Digitar os dados manualmente
        </Txt>
        <Icon name={manual ? 'chevron-up' : 'chevron-down'} size={20} color={palette.textSecondary} />
      </Pressable>
      {manual ? (
        <View style={styles.form}>
          <View style={styles.row}>
            <View style={styles.hostField}>
              <TextField label="Endereço IP" keyboardType="numbers-and-punctuation" placeholder="192.168.0.10" {...field('host')} />
            </View>
            <View style={styles.portField}>
              <TextField label="Porta" keyboardType="number-pad" {...field('port')} />
            </View>
          </View>
          <TextField label="Código de pareamento" keyboardType="number-pad" placeholder="0000 0000" maxLength={9} mono {...field('code')} />
          <TextField label="Identidade SHA-256 do PC" placeholder="64 caracteres hexadecimais" multiline mono {...field('fingerprint')} />
          <Button label="Pedir pareamento" icon="link" onPress={submit} />
        </View>
      ) : null}
    </View>
  );
}

const makeStyles = (palette: Palette) => ({
  root: {gap: space.lg},
  section: {gap: space.sm},
  sectionHeader: {flexDirection: 'row' as const, alignItems: 'center' as const, gap: space.sm},
  flex: {flex: 1},
  found: {
    flexDirection: 'row' as const,
    alignItems: 'center' as const,
    gap: space.md,
    minHeight: 56,
    paddingHorizontal: space.md,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: palette.border,
    backgroundColor: palette.surfaceSunken,
    overflow: 'hidden' as const,
  },
  toggle: {flexDirection: 'row' as const, alignItems: 'center' as const, minHeight: 48},
  form: {gap: space.md},
  row: {flexDirection: 'row' as const, gap: space.sm},
  hostField: {flex: 1},
  portField: {width: 96},
});
