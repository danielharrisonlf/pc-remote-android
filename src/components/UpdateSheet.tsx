import React from 'react';
import {Pressable, View} from 'react-native';
import {remote} from '../communication/remote';
import {APP_VERSION, updates} from '../communication/updates';
import {useUpdates} from '../hooks/useRemote';
import {usePalette, useStyles} from '../theme/ThemeProvider';
import {radius, space, type Palette} from '../theme/tokens';
import {Button, IconButton} from './ui/Button';
import {Icon} from './ui/Icon';
import {Markdown} from './ui/Markdown';
import {Sheet} from './ui/Sheet';
import {useSnackbar} from './ui/Snackbar';
import {Txt} from './ui/Txt';

const dateFormat = new Intl.DateTimeFormat('pt-BR', {day: 'numeric', month: 'long', year: 'numeric'});

function formatSize(bytes: number): string {
  return bytes > 0 ? `${(bytes / 1024 / 1024).toLocaleString('pt-BR', {maximumFractionDigits: 1})} MB` : '';
}

/** Friendly banner under the top bar while a newer, not dismissed release exists. */
export function UpdateBanner({onOpen}: {onOpen: () => void}) {
  const palette = usePalette();
  const styles = useStyles(makeStyles);
  const {release} = useUpdates();
  if (!release) {
    return null;
  }
  // The whole banner opens the notes; only the close button dismisses.
  return (
    <View style={styles.banner}>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={`Versão ${release.version} disponível. Ver novidades`}
        onPress={onOpen}
        android_ripple={{color: palette.ripple}}
        style={styles.bannerMain}>
        <Icon name="circle-arrow-up" size={22} color={palette.accentText} />
        <View style={styles.bannerText}>
          <Txt variant="label" tone="accent" numberOfLines={1}>
            Versão {release.version} disponível
          </Txt>
          <Txt variant="caption" tone="secondary" numberOfLines={1}>
            Toque para ver as novidades. Você usa a {APP_VERSION}.
          </Txt>
        </View>
      </Pressable>
      <IconButton icon="x" label="Dispensar esta versão" size={40} onPress={() => remote.skipUpdate(release.version)} />
    </View>
  );
}

export function UpdateSheet({visible, onClose}: {visible: boolean; onClose: () => void}) {
  const styles = useStyles(makeStyles);
  const snackbar = useSnackbar();
  const {release, apk, available} = useUpdates();
  if (!release) {
    return null;
  }
  const published = release.publishedAt ? dateFormat.format(new Date(release.publishedAt)) : null;
  const download = () => {
    updates
      .download()
      .then(() => {
        onClose();
        snackbar.show('Download aberto no navegador. Toque no APK baixado para instalar a atualização.', 'info');
      })
      .catch(error => snackbar.show(String((error as Error).message), 'error'));
  };
  return (
    <Sheet
      visible={visible}
      onClose={onClose}
      scroll
      title={available ? `PC Remote ${release.version}` : `Notas da versão ${release.version}`}
      subtitle={`${published ? `Publicada em ${published}. ` : ''}Você usa a ${APP_VERSION}.`}
      footer={
        available ? (
          <>
            <Button
              label="Agora não"
              variant="text"
              onPress={() => {
                remote.skipUpdate(release.version);
                onClose();
              }}
            />
            <Button label={apk ? `Baixar APK ${formatSize(apk.size)}`.trim() : 'Abrir download'} icon="download" grow onPress={download} />
          </>
        ) : (
          <Button label="Ver no GitHub" icon="external-link" variant="outlined" grow onPress={() => updates.openReleasePage().catch(() => undefined)} />
        )
      }>
      <Markdown source={release.notes} />
      {available ? (
        <View style={styles.note}>
          <Txt variant="caption" tone="secondary">
            O Android pede para autorizar a instalação a partir do navegador na primeira vez. Seus pareamentos e preferências são mantidos. Confira o
            SHA-256 publicado na release.
          </Txt>
        </View>
      ) : null}
    </Sheet>
  );
}

const makeStyles = (palette: Palette) => ({
  banner: {
    flexDirection: 'row' as const,
    alignItems: 'center' as const,
    marginHorizontal: space.lg,
    marginBottom: space.sm,
    paddingRight: space.xs,
    borderRadius: radius.lg,
    backgroundColor: palette.accentSoft,
    overflow: 'hidden' as const,
  },
  bannerMain: {flex: 1, flexDirection: 'row' as const, alignItems: 'center' as const, gap: space.md, paddingVertical: space.md, paddingLeft: space.lg},
  bannerText: {flex: 1},
  note: {padding: space.md, borderRadius: radius.md, backgroundColor: palette.surfaceSunken},
});
