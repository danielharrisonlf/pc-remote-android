import React, {useState} from 'react';
import {ActivityIndicator, Alert, Pressable, ScrollView, View} from 'react-native';
import {remote, type RemoteView, type ThemePreference} from '../communication/remote';
import {APP_VERSION, updates} from '../communication/updates';
import {Button} from '../components/ui/Button';
import {Divider, FingerprintStrip, StatusChip, type StatusTone} from '../components/ui/Bits';
import {Card} from '../components/ui/Card';
import {Icon} from '../components/ui/Icon';
import {Segmented} from '../components/ui/Segmented';
import {Sheet} from '../components/ui/Sheet';
import {useSnackbar} from '../components/ui/Snackbar';
import {SwitchRow} from '../components/ui/SwitchRow';
import {TextField} from '../components/ui/TextField';
import {Txt} from '../components/ui/Txt';
import {fingerprintGroups} from '../domain/fingerprint';
import {useLatency, useRemote, useSavedComputer, useUpdates} from '../hooks/useRemote';
import NativePCRemote from '../integrations/NativePCRemote';
import {usePalette, useStyles} from '../theme/ThemeProvider';
import {radius, space, type Palette} from '../theme/tokens';
import {PairingPanel} from './PairingPanel';

export function statusPresentation(status: RemoteView['status']): {tone: StatusTone; label: string} {
  switch (status) {
    case 'conectado':
      return {tone: 'success', label: 'Conectado'};
    case 'conectando':
      return {tone: 'warning', label: 'Conectando'};
    case 'reconectando':
      return {tone: 'warning', label: 'Reconectando'};
    case 'pareando':
      return {tone: 'accent', label: 'Pareando'};
    case 'aguardando aprovação':
      return {tone: 'accent', label: 'Aguardando o PC'};
    case 'desconectado':
      return {tone: 'neutral', label: 'Desconectado'};
  }
}

/** 16 groups of the saved SHA-256, or same-sized placeholders while it loads. */
function identityGroups(fingerprint: string | undefined): string[] {
  const groups = fingerprint ? fingerprintGroups(fingerprint) : [];
  return groups.length === 16 ? groups : new Array<string>(16).fill('····');
}

function latencyQuality(ms: number): {label: string; tone: 'success' | 'warning' | 'secondary'} {
  if (ms < 40) {
    return {label: 'Excelente', tone: 'success'};
  }
  if (ms < 120) {
    return {label: 'Boa', tone: 'secondary'};
  }
  return {label: 'Lenta', tone: 'warning'};
}

export function ConnectionScreen({onOpenUpdate}: {onOpenUpdate: () => void}) {
  const view = useRemote();
  const palette = usePalette();
  const styles = useStyles(makeStyles);
  const snackbar = useSnackbar();
  const update = useUpdates();
  const [pairingSheet, setPairingSheet] = useState(false);
  const [editing, setEditing] = useState(false);
  const [address, setAddress] = useState({host: '', port: ''});
  const [savingAddress, setSavingAddress] = useState(false);
  const saved = useSavedComputer(`${view.pcName}|${view.status}|${savingAddress}`);
  const connected = view.status === 'conectado';
  const latency = useLatency(connected);
  const paired = Boolean(view.pcName);
  const pairing = view.status === 'pareando' || view.status === 'aguardando aprovação';
  const status = statusPresentation(view.status);

  const startEditing = () => {
    setAddress({host: saved?.host ?? '', port: String(saved?.port ?? 43127)});
    setEditing(true);
  };

  const saveAddress = () => {
    const port = Number(address.port);
    if (!address.host.trim() || !Number.isInteger(port) || port < 1 || port > 65535) {
      snackbar.show('Informe um endereço e uma porta válidos.', 'error');
      return;
    }
    setSavingAddress(true);
    NativePCRemote.updateAddress(address.host.trim(), port)
      .then(() => {
        setEditing(false);
        snackbar.show('Endereço atualizado. Reconectando…', 'success');
      })
      .catch(error => snackbar.show(String((error as Error).message), 'error'))
      .finally(() => setSavingAddress(false));
  };

  const forget = () =>
    Alert.alert('Remover este PC?', 'A credencial deste celular será apagada. No Windows, revogue também o acesso em Dispositivos.', [
      {text: 'Cancelar', style: 'cancel'},
      {text: 'Remover', style: 'destructive', onPress: () => remote.forget()},
    ]);

  const check = () => {
    updates.check().then(lookup => {
      if (lookup.kind === 'unavailable') {
        snackbar.show(lookup.message, 'error');
      } else if (updates.getSnapshot().available) {
        onOpenUpdate();
      } else {
        snackbar.show('Você já está na versão mais recente.', 'success');
      }
    });
  };

  return (
    <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
      {pairing ? (
        <Card tone="attention">
          <View style={styles.waiting}>
            <ActivityIndicator color={palette.warning} />
            <View style={styles.flex}>
              <Txt variant="title">Confirme no computador</Txt>
              <Txt tone="secondary">
                {view.status === 'pareando' ? 'Enviando o pedido de pareamento…' : 'Na janela do PC Remote no Windows, clique em Permitir para liberar este celular.'}
              </Txt>
            </View>
          </View>
          <Button label="Cancelar pedido" variant="outlined" onPress={() => remote.disconnect()} />
        </Card>
      ) : null}

      {/* Stays mounted while a request is pending, so a rejected or failed
          attempt keeps what was typed for a quick retry. */}
      {!paired ? (
        <Card title="Conectar a um computador" subtitle="O celular e o PC precisam estar na mesma rede Wi-Fi.">
          <PairingPanel />
        </Card>
      ) : null}

      {paired ? (
        <Card>
          <View style={styles.pcHeader}>
            <View style={styles.pcIcon}>
              <Icon name="monitor" size={24} color={palette.accentText} />
            </View>
            <View style={styles.flex}>
              <Txt variant="headline" numberOfLines={1}>
                {view.pcName}
              </Txt>
              <StatusChip tone={status.tone} label={status.label} />
            </View>
          </View>

          <View style={styles.stats}>
            <View style={styles.stat}>
              <Txt variant="caption" tone="secondary">
                Latência
              </Txt>
              {connected && latency !== null ? (
                <>
                  <Txt variant="title" tabular>
                    {latency} ms
                  </Txt>
                  <Txt variant="caption" tone={latencyQuality(latency).tone}>
                    {latencyQuality(latency).label}
                  </Txt>
                </>
              ) : (
                <Txt variant="title" tone="tertiary">
                  —
                </Txt>
              )}
            </View>
            <View style={[styles.stat, styles.statWide]}>
              <Txt variant="caption" tone="secondary">
                Endereço salvo
              </Txt>
              <Txt variant="mono" numberOfLines={1}>
                {saved ? `${saved.host}:${saved.port}` : '—'}
              </Txt>
            </View>
          </View>

          {/* Fixed structure: the saved identity arrives asynchronously, and a
              late insertion here would shift every control below it. */}
          <View style={styles.identity}>
            <View style={styles.identityHeader}>
              <Icon name="shield-check" size={18} color={palette.success} />
              <Txt variant="label" style={styles.flex}>
                Identidade conferida a cada conexão
              </Txt>
            </View>
            <View style={styles.groups}>
              {identityGroups(saved?.fingerprint).map((group, index) => (
                <Txt key={index} variant="mono" tone={saved?.fingerprint ? 'primary' : 'tertiary'} style={styles.group}>
                  {group}
                </Txt>
              ))}
            </View>
            <FingerprintStrip value={saved?.fingerprint ?? ''} />
            <Txt variant="caption" tone="tertiary">
              As cores e os blocos devem ser iguais aos de Diagnóstico no PC.
            </Txt>
          </View>

          <View style={styles.row}>
            {connected ? (
              <Button label="Desconectar" icon="unplug" variant="outlined" grow onPress={() => remote.disconnect()} />
            ) : (
              <Button label="Reconectar" icon="refresh-cw" grow disabled={pairing} onPress={() => NativePCRemote.reconnect()} />
            )}
            <Button label="Outro PC" icon="scan-qr-code" variant="tonal" grow onPress={() => setPairingSheet(true)} />
          </View>

          <Divider />
          <Pressable accessibilityRole="button" accessibilityState={{expanded: editing}} onPress={() => (editing ? setEditing(false) : startEditing())} style={styles.disclosure}>
            <Icon name="pencil" size={18} color={palette.textSecondary} />
            <Txt variant="label" style={styles.flex}>
              Corrigir endereço
            </Txt>
            <Icon name={editing ? 'chevron-up' : 'chevron-down'} size={20} color={palette.textSecondary} />
          </Pressable>
          {editing ? (
            <View style={styles.editForm}>
              <Txt variant="caption" tone="secondary">
                Use quando o IP do PC mudar e a busca automática não o encontrar. A identidade continua a mesma.
              </Txt>
              <View style={styles.row}>
                <View style={styles.flex}>
                  <TextField label="Endereço IP" keyboardType="numbers-and-punctuation" value={address.host} onChangeText={host => setAddress(current => ({...current, host}))} />
                </View>
                <View style={styles.portField}>
                  <TextField label="Porta" keyboardType="number-pad" value={address.port} onChangeText={port => setAddress(current => ({...current, port}))} />
                </View>
              </View>
              <Button label="Salvar e reconectar" loading={savingAddress} onPress={saveAddress} />
            </View>
          ) : null}
          <Button label="Remover este PC" icon="trash-2" variant="danger" onPress={forget} />
        </Card>
      ) : null}

      <Card title="Preferências">
        <View style={styles.preference}>
          <Txt variant="bodyStrong">Tema</Txt>
          <Segmented<ThemePreference>
            label="Tema"
            value={view.theme}
            onChange={theme => remote.setTheme(theme)}
            options={[
              {value: 'system', label: 'Sistema', icon: 'monitor'},
              {value: 'light', label: 'Claro', icon: 'sun'},
              {value: 'dark', label: 'Escuro', icon: 'moon'},
            ]}
          />
        </View>
        <SwitchRow icon="hand" label="Feedback tátil" description="Vibração curta a cada clique, toque e ação confirmada." value={view.haptics} onValueChange={enabled => remote.setHaptics(enabled)} last />
      </Card>

      <Card
        title="Atualizações"
        subtitle={`Versão instalada ${APP_VERSION}`}
        trailing={update.available && update.release ? <StatusChip tone="accent" label={`${update.release.version} disponível`} /> : null}>
        <Txt variant="caption" tone={update.lookup?.kind === 'unavailable' ? 'warning' : 'secondary'}>
          {update.checking
            ? 'Verificando…'
            : !update.lookup
              ? 'A verificação automática roda ao abrir o app e a cada 6 horas.'
              : update.lookup.kind === 'unavailable'
                ? update.lookup.message
                : update.available
                  ? 'Há uma versão nova. Veja as novidades antes de baixar o APK.'
                  : 'Você está na versão mais recente.'}
        </Txt>
        <View style={styles.row}>
          <Button label="Verificar agora" icon="refresh-cw" variant="outlined" grow loading={update.checking} onPress={check} />
          {update.available ? <Button label="Ver novidades" icon="circle-arrow-up" grow onPress={onOpenUpdate} /> : null}
        </View>
      </Card>

      <Sheet visible={pairingSheet} onClose={() => setPairingSheet(false)} title="Parear outro PC" subtitle="O PC atual continua salvo até o novo pareamento ser aprovado." scroll>
        <PairingPanel onRequested={() => setPairingSheet(false)} />
      </Sheet>
    </ScrollView>
  );
}

const makeStyles = (palette: Palette) => ({
  content: {padding: space.lg, gap: space.lg, paddingBottom: space.xxxl},
  flex: {flex: 1},
  waiting: {flexDirection: 'row' as const, gap: space.lg, alignItems: 'flex-start' as const},
  pcHeader: {flexDirection: 'row' as const, alignItems: 'center' as const, gap: space.lg},
  pcIcon: {
    width: 52,
    height: 52,
    borderRadius: radius.lg,
    backgroundColor: palette.accentSoft,
    alignItems: 'center' as const,
    justifyContent: 'center' as const,
  },
  stats: {flexDirection: 'row' as const, gap: space.sm},
  stat: {
    flex: 1,
    gap: 2,
    padding: space.md,
    borderRadius: radius.md,
    backgroundColor: palette.surfaceSunken,
    borderWidth: 1,
    borderColor: palette.border,
  },
  statWide: {flex: 1.6},
  identity: {gap: space.sm},
  identityHeader: {flexDirection: 'row' as const, alignItems: 'center' as const, gap: space.sm},
  groups: {
    flexDirection: 'row' as const,
    flexWrap: 'wrap' as const,
    rowGap: 4,
    padding: space.md,
    borderRadius: radius.md,
    backgroundColor: palette.surfaceSunken,
  },
  group: {width: '25%' as const, letterSpacing: 0.5},
  row: {flexDirection: 'row' as const, gap: space.sm},
  disclosure: {flexDirection: 'row' as const, alignItems: 'center' as const, gap: space.sm, minHeight: 48},
  editForm: {gap: space.md},
  portField: {width: 96},
  preference: {gap: space.sm},
});
