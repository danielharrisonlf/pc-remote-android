import React from 'react';
import {Text, View} from 'react-native';
import {Button} from './Controls';
import {styles, type Palette} from '../theme/theme';
import type {Action, Session} from '../domain/protocol';

export function SessionControls({session, palette, action, connected}: {session?: Session; palette: Palette; action: (a: Action) => void; connected: boolean}) {
  const cap = session?.capabilities;
  const command = (value: 'play' | 'pause' | 'previous' | 'next' | 'seek', offsetSeconds?: -10 | 10) => {
    if (!session) {return;}
    action({kind: 'media.control', sessionId: session.id, command: value, ...(offsetSeconds ? {offsetSeconds} : {})});
  };
  const unavailable = 'O player não oferece esta ação.';
  return <>
    <Text style={[styles.mediaTitle, {color: palette.ink}]} numberOfLines={2}>{session ? (session.title || 'Mídia sem título') : 'Nenhuma mídia ativa'}</Text>
    <Text style={[styles.body, {color: palette.soft}]}>{session?.app || 'Abra uma música ou vídeo no PC.'}</Text>
    <View style={styles.row}><Button label="Faixa anterior" onPress={() => command('previous')} disabled={!connected || !cap?.previous} secondary palette={palette} hint={unavailable} /><Button label="Próxima faixa" onPress={() => command('next')} disabled={!connected || !cap?.next} secondary palette={palette} hint={unavailable} /></View>
    <View style={styles.row}><Button label={session?.playing ? 'Pausar' : 'Reproduzir'} onPress={() => command(session?.playing ? 'pause' : 'play')} disabled={!connected || !(session?.playing ? cap?.pause : cap?.play)} palette={palette} hint={unavailable} /></View>
    <Text style={[styles.label, {color: palette.soft, marginTop: 14}]}>Posição do vídeo</Text>
    <View style={styles.row}><Button label="−10 s" onPress={() => command('seek', -10)} disabled={!connected || !cap?.seek} secondary palette={palette} hint={unavailable} /><Button label="+10 s" onPress={() => command('seek', 10)} disabled={!connected || !cap?.seek} secondary palette={palette} hint={unavailable} /></View>
  </>;
}
