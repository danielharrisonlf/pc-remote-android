import React, {useState} from 'react';
import {PermissionsAndroid, Platform, ScrollView, View} from 'react-native';
import {remote} from '../communication/remote';
import {Button} from '../components/ui/Button';
import {Divider, EmptyState, StatusChip} from '../components/ui/Bits';
import {Card} from '../components/ui/Card';
import {useSnackbar} from '../components/ui/Snackbar';
import {SwitchRow} from '../components/ui/SwitchRow';
import {Txt} from '../components/ui/Txt';
import {useHaptics, useRemote} from '../hooks/useRemote';
import {useStyles} from '../theme/ThemeProvider';
import {radius, space, type Palette} from '../theme/tokens';

const PREVIEW_LINES = 8;

export function ClipboardScreen() {
  const view = useRemote();
  const styles = useStyles(makeStyles);
  const snackbar = useSnackbar();
  const haptic = useHaptics();
  const [busy, setBusy] = useState<'send' | 'fetch' | 'copy' | null>(null);
  const [expanded, setExpanded] = useState(false);
  const connected = view.status === 'conectado';
  const live = !view.paused && (view.upload || view.download);

  const attempt = (key: 'send' | 'fetch' | 'copy', task: () => Promise<void>, success: string) => {
    setBusy(key);
    task()
      .then(() => {
        haptic('confirm');
        snackbar.show(success, 'success');
      })
      .catch(error => snackbar.show(String((error as Error).message), 'error'))
      .finally(() => setBusy(null));
  };

  const toggleDownload = async (enabled: boolean) => {
    if (enabled && Platform.OS === 'android' && Number(Platform.Version) >= 33) {
      await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS, {
        title: 'Aviso de texto recebido',
        message: 'Permita avisos para abrir textos recebidos quando o aplicativo estiver em segundo plano.',
        buttonPositive: 'Permitir',
      });
    }
    remote.setSync({download: enabled});
  };

  const received = view.receivedText;
  return (
    <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
      <Card
        title="Recebido do PC"
        subtitle={received ? `${Array.from(received).length} caracteres. Fica só na memória do app.` : undefined}
        trailing={<StatusChip tone={live ? 'success' : 'neutral'} label={view.paused ? 'Pausada' : live ? 'Em tempo real' : 'Manual'} />}>
        {received ? (
          <>
            <View style={styles.viewer}>
              <Txt selectable numberOfLines={expanded ? undefined : PREVIEW_LINES} style={styles.viewerText}>
                {received}
              </Txt>
            </View>
            {received.split('\n').length > PREVIEW_LINES || received.length > 400 ? (
              <Button label={expanded ? 'Mostrar menos' : 'Mostrar tudo'} variant="text" onPress={() => setExpanded(!expanded)} style={styles.expand} />
            ) : null}
            <View style={styles.row}>
              <Button label="Copiar no celular" icon="copy" grow loading={busy === 'copy'} onPress={() => attempt('copy', () => remote.copyReceived(), 'Copiado na área de transferência do celular.')} />
              <Button label="Limpar" icon="eraser" variant="outlined" onPress={() => remote.clearReceived()} />
            </View>
          </>
        ) : (
          <EmptyState icon="clipboard-paste" title="Nada recebido nesta sessão">
            {view.download && !view.paused ? 'Copie um texto no Windows e ele aparece aqui.' : 'Use “Buscar do PC” ou ligue PC → celular abaixo.'}
          </EmptyState>
        )}
      </Card>

      <Card title="Enviar ao PC" subtitle={view.sharedText ? 'Texto recebido pelo menu Compartilhar, pronto para enviar.' : 'Envia o que está copiado neste celular.'}>
        {view.sharedText ? (
          <View style={styles.viewer}>
            <Txt numberOfLines={4} style={styles.viewerText}>
              {view.sharedText}
            </Txt>
          </View>
        ) : null}
        <View style={styles.row}>
          <Button
            label={view.sharedText ? 'Enviar texto' : 'Enviar ao PC'}
            icon="send"
            grow
            disabled={!connected}
            loading={busy === 'send'}
            onPress={() => attempt('send', () => remote.sendClipboard(), 'Texto enviado ao PC.')}
          />
          <Button
            label="Buscar do PC"
            icon="download"
            variant="tonal"
            grow
            disabled={!connected}
            loading={busy === 'fetch'}
            onPress={() => attempt('fetch', () => remote.getClipboard(), 'Pedido enviado. O texto do PC aparece acima.')}
          />
        </View>
        {!connected ? (
          <Txt variant="caption" tone="secondary">
            Conecte-se ao PC para enviar ou buscar textos.
          </Txt>
        ) : null}
      </Card>

      <Card title="Sincronização">
        <View>
          <SwitchRow
            icon="zap"
            label="Sincronizar em tempo real"
            description="Desligue para pausar as duas direções sem perder as escolhas abaixo."
            value={!view.paused}
            onValueChange={enabled => remote.setSync({paused: !enabled})}
          />
          <SwitchRow
            icon="send"
            label="Celular → PC"
            description="Envia novas cópias feitas com este app aberto."
            value={view.upload}
            disabled={view.paused}
            onValueChange={upload => remote.setSync({upload})}
          />
          <SwitchRow
            icon="download"
            label="PC → celular"
            description="Recebe novas cópias do Windows, com aviso quando o app está em segundo plano."
            value={view.download}
            disabled={view.paused}
            onValueChange={enabled => toggleDownload(enabled).catch(() => remote.setSync({download: enabled}))}
          />
          <SwitchRow
            icon="clipboard-copy"
            label="Aplicar automaticamente"
            description="Coloca o texto recebido direto na área de transferência quando o app está em foco."
            value={view.autoApply}
            disabled={view.paused || !view.download}
            onValueChange={autoApply => remote.setSync({autoApply})}
            last
          />
        </View>
        <Divider />
        <Txt variant="caption" tone="secondary">
          O Android só deixa ler cópias de outros apps com o PC Remote aberto. Para enviar de outro app, use Compartilhar → PC Remote. O PC também
          precisa liberar a mesma direção.
        </Txt>
      </Card>
    </ScrollView>
  );
}

const makeStyles = (palette: Palette) => ({
  content: {padding: space.lg, gap: space.lg, paddingBottom: space.xxxl},
  viewer: {
    padding: space.lg,
    borderRadius: radius.lg,
    backgroundColor: palette.surfaceSunken,
    borderWidth: 1,
    borderColor: palette.border,
  },
  viewerText: {fontSize: 15, lineHeight: 22},
  expand: {alignSelf: 'flex-start' as const, paddingHorizontal: 0},
  row: {flexDirection: 'row' as const, gap: space.sm},
});
