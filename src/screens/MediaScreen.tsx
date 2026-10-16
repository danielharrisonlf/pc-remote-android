import React, {useCallback} from 'react';
import {Pressable, ScrollView, View} from 'react-native';
import Slider from '@react-native-community/slider';
import {remote} from '../communication/remote';
import {Button, IconButton} from '../components/ui/Button';
import {EmptyState} from '../components/ui/Bits';
import {Card} from '../components/ui/Card';
import {Icon, type IconName} from '../components/ui/Icon';
import {useSnackbar} from '../components/ui/Snackbar';
import {Txt} from '../components/ui/Txt';
import type {Action, Session} from '../domain/protocol';
import {useHaptics, useRemote} from '../hooks/useRemote';
import {usePalette, useStyles} from '../theme/ThemeProvider';
import {radius, space, type Palette} from '../theme/tokens';
import type {TabName} from './tabs';

type MediaCommand = 'play' | 'pause' | 'previous' | 'next' | 'seek';

export function MediaScreen({onNavigate}: {onNavigate: (tab: TabName) => void}) {
  const view = useRemote();
  const palette = usePalette();
  const styles = useStyles(makeStyles);
  const snackbar = useSnackbar();
  const haptic = useHaptics();
  const connected = view.status === 'conectado';
  const audio = view.state?.audio;
  const sessions = view.state?.media.sessions ?? [];
  const session = sessions.find(item => item.id === view.selectedSessionId);
  const volume = Math.round(view.draggingVolume ?? audio?.percent ?? 0);
  const audioReady = connected && !!audio?.available;

  const run = useCallback(
    (action: Action) => {
      haptic('tap');
      remote.request(action).catch(error => snackbar.show(String((error as Error).message), 'error'));
    },
    [haptic, snackbar],
  );

  if (!connected) {
    return (
      <View style={styles.center}>
        <EmptyState icon="audio-lines" title="Mídia e volume do PC" action={<Button label="Abrir Conexão" variant="tonal" onPress={() => onNavigate('connection')} />}>
          Conecte-se ao computador para ajustar o volume e controlar o que está tocando.
        </EmptyState>
      </View>
    );
  }

  return (
    <ScrollView contentContainerStyle={styles.content}>
      <Card>
        <View style={styles.volumeHeader}>
          <View style={styles.volumeValue}>
            <Txt variant="display" tabular accessibilityLabel={`Volume ${volume} por cento`}>
              {volume}
            </Txt>
            <Txt variant="title" tone="secondary" style={styles.percent}>
              %
            </Txt>
          </View>
          <View style={styles.volumeState}>
            <Icon name={audio?.muted ? 'volume-x' : 'volume-2'} size={20} color={audio?.muted ? palette.warning : palette.textSecondary} />
            <Txt variant="caption" tone={audio?.muted ? 'warning' : 'secondary'}>
              {!audio?.available ? 'Saída de áudio indisponível' : audio.muted ? 'Sem som' : 'Saída padrão do Windows'}
            </Txt>
          </View>
        </View>
        <Slider
          accessibilityLabel="Volume do PC"
          minimumValue={0}
          maximumValue={100}
          step={1}
          value={volume}
          disabled={!audioReady}
          minimumTrackTintColor={palette.accent}
          maximumTrackTintColor={palette.borderStrong}
          thumbTintColor={palette.accent}
          onSlidingStart={value => remote.beginVolume(value)}
          onValueChange={value => remote.updateVolume(value)}
          onSlidingComplete={value => {
            haptic('tap');
            remote.endVolume(value);
          }}
          style={styles.slider}
        />
        <View style={styles.row}>
          <Button label="−5%" variant="tonal" grow disabled={!audioReady} onPress={() => run({kind: 'volume.step', delta: -5})} />
          <Button
            label={audio?.muted ? 'Som' : 'Mudo'}
            icon={audio?.muted ? 'volume-2' : 'volume-x'}
            variant={audio?.muted ? 'filled' : 'outlined'}
            grow
            disabled={!audioReady}
            onPress={() => run({kind: 'volume.mute', muted: !audio?.muted})}
          />
          <Button label="+5%" variant="tonal" grow disabled={!audioReady} onPress={() => run({kind: 'volume.step', delta: 5})} />
        </View>
      </Card>

      <Card title="Tocando agora">
        {sessions.length > 1 ? (
          <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.chips}>
            {sessions.map(item => (
              <SessionChip key={item.id} session={item} selected={item.id === view.selectedSessionId} onPress={() => remote.selectSession(item.id)} />
            ))}
          </ScrollView>
        ) : null}
        {session ? <NowPlaying session={session} onCommand={(command, offsetSeconds) => run({kind: 'media.control', sessionId: session.id, command, ...(offsetSeconds ? {offsetSeconds} : {})})} /> : (
          <EmptyState icon="music" title="Nada tocando">
            Abra uma música ou um vídeo no PC. Players que publicam controles no Windows aparecem aqui.
          </EmptyState>
        )}
      </Card>
    </ScrollView>
  );
}

function SessionChip({session, selected, onPress}: {session: Session; selected: boolean; onPress: () => void}) {
  const palette = usePalette();
  const styles = useStyles(makeStyles);
  return (
    <Pressable
      accessibilityRole="radio"
      accessibilityState={{checked: selected}}
      onPress={onPress}
      android_ripple={{color: palette.ripple}}
      style={[styles.chip, selected ? styles.chipSelected : null]}>
      {selected ? <Icon name="check" size={16} color={palette.accentText} /> : session.playing ? <Icon name="play" size={14} color={palette.textSecondary} /> : null}
      <Txt variant="label" tone={selected ? 'accent' : 'secondary'} numberOfLines={1} style={styles.chipText}>
        {session.app || session.title || 'Player'}
      </Txt>
    </Pressable>
  );
}

function NowPlaying({session, onCommand}: {session: Session; onCommand: (command: MediaCommand, offsetSeconds?: -10 | 10) => void}) {
  const palette = usePalette();
  const styles = useStyles(makeStyles);
  const cap = session.capabilities;
  const canToggle = session.playing ? cap.pause : cap.play;
  const control = (icon: IconName, label: string, enabled: boolean, onPress: () => void) => <IconButton icon={icon} label={label} disabled={!enabled} onPress={onPress} size={52} />;
  return (
    <View style={styles.player}>
      <View style={styles.playerText}>
        <Txt variant="caption" tone="secondary" numberOfLines={1}>
          {session.app || 'Player do Windows'}
        </Txt>
        <Txt variant="headline" numberOfLines={2}>
          {session.title || 'Mídia sem título'}
        </Txt>
      </View>
      <View style={styles.controls}>
        {control('rotate-ccw', 'Voltar 10 segundos', cap.seek, () => onCommand('seek', -10))}
        {control('skip-back', 'Faixa anterior', cap.previous, () => onCommand('previous'))}
        {/* The fill lives on a plain View: a borderless ripple on the Pressable
            itself replaces its background drawable on Android. */}
        <View style={[styles.play, !canToggle ? styles.disabled : null]}>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel={session.playing ? 'Pausar' : 'Reproduzir'}
            accessibilityState={{disabled: !canToggle}}
            disabled={!canToggle}
            onPress={() => onCommand(session.playing ? 'pause' : 'play')}
            android_ripple={{color: palette.ripple}}
            style={({pressed}) => [styles.playHit, pressed ? styles.playPressed : null]}>
            <Icon name={session.playing ? 'pause' : 'play'} size={32} color={palette.onAccent} />
          </Pressable>
        </View>
        {control('skip-forward', 'Próxima faixa', cap.next, () => onCommand('next'))}
        {control('rotate-cw', 'Avançar 10 segundos', cap.seek, () => onCommand('seek', 10))}
      </View>
      {!cap.seek || !cap.previous || !cap.next ? (
        <Txt variant="caption" tone="tertiary" center>
          Controles apagados não são oferecidos por este player.
        </Txt>
      ) : null}
    </View>
  );
}

const makeStyles = (palette: Palette) => ({
  content: {padding: space.lg, gap: space.lg, paddingBottom: space.xxxl},
  center: {flex: 1, justifyContent: 'center' as const, padding: space.lg},
  volumeHeader: {flexDirection: 'row' as const, alignItems: 'flex-end' as const, justifyContent: 'space-between' as const, gap: space.md},
  volumeValue: {flexDirection: 'row' as const, alignItems: 'baseline' as const},
  percent: {marginLeft: 2},
  volumeState: {flexDirection: 'row' as const, alignItems: 'center' as const, gap: 6, flexShrink: 1, paddingBottom: 10},
  slider: {width: '100%' as const, height: 48},
  row: {flexDirection: 'row' as const, gap: space.sm},
  chips: {gap: space.sm},
  chip: {
    flexDirection: 'row' as const,
    alignItems: 'center' as const,
    gap: 6,
    height: 40,
    maxWidth: 220,
    paddingHorizontal: 14,
    borderRadius: radius.sm,
    borderWidth: 1,
    borderColor: palette.borderStrong,
    overflow: 'hidden' as const,
  },
  chipSelected: {backgroundColor: palette.accentSoft, borderColor: palette.accentSoft},
  chipText: {flexShrink: 1},
  player: {gap: space.xl},
  playerText: {gap: 4},
  controls: {flexDirection: 'row' as const, alignItems: 'center' as const, justifyContent: 'space-between' as const},
  play: {
    width: 76,
    height: 76,
    borderRadius: 38,
    backgroundColor: palette.accent,
    alignItems: 'center' as const,
    justifyContent: 'center' as const,
    overflow: 'hidden' as const,
  },
  playHit: {flex: 1, alignItems: 'center' as const, justifyContent: 'center' as const},
  playPressed: {backgroundColor: palette.accentPressed},
  disabled: {opacity: 0.4},
});
