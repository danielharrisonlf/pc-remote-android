import React, {useState} from 'react';
import {TextInput, View} from 'react-native';
import {remote} from '../communication/remote';
import {Button, IconButton} from '../components/ui/Button';
import {Sheet} from '../components/ui/Sheet';
import {Txt} from '../components/ui/Txt';
import {inputTextIsAllowed, utf8Size} from '../domain/protocol';
import {useHaptics} from '../hooks/useRemote';
import {usePalette, useStyles} from '../theme/ThemeProvider';
import {radius, space, type Palette} from '../theme/tokens';

const MAX_BYTES = 1024;

/** Compose text on the phone, check it, then type it into the focused PC window. */
export function KeyboardSheet({visible, onClose}: {visible: boolean; onClose: () => void}) {
  const palette = usePalette();
  const styles = useStyles(makeStyles);
  const haptic = useHaptics();
  const [draft, setDraft] = useState('');
  const [sending, setSending] = useState(false);
  const [feedback, setFeedback] = useState<{tone: 'success' | 'danger'; text: string} | null>(null);
  const bytes = draft ? utf8Size(draft) : 0;
  const valid = inputTextIsAllowed(draft);

  const send = async (withEnter: boolean) => {
    if (!valid || sending) {
      return;
    }
    setSending(true);
    setFeedback(null);
    try {
      await remote.inputAction({kind: 'keyboard.text', text: draft});
      if (withEnter) {
        await remote.inputAction({kind: 'keyboard.key', key: 'enter'});
      }
      haptic('confirm');
      setDraft('');
      setFeedback({tone: 'success', text: withEnter ? 'Texto enviado e Enter pressionado.' : 'Texto enviado ao PC.'});
    } catch (error) {
      setFeedback({tone: 'danger', text: (error as Error).message});
    } finally {
      setSending(false);
    }
  };

  const problem = draft && !valid ? (bytes > MAX_BYTES ? 'Texto acima de 1024 bytes. Envie em partes.' : 'Quebras de linha e caracteres de controle não são enviados.') : null;

  return (
    <Sheet
      visible={visible}
      onClose={onClose}
      title="Digitar no PC"
      subtitle="O texto vai para a janela em foco no Windows."
      footer={
        <>
          <Button label="Enviar + Enter" variant="tonal" grow disabled={!valid} loading={sending} onPress={() => send(true)} />
          <Button label="Enviar" icon="send" grow disabled={!valid} loading={sending} onPress={() => send(false)} />
        </>
      }>
      <View style={styles.field}>
        <TextInput
          accessibilityLabel="Texto para digitar no PC"
          value={draft}
          onChangeText={text => {
            setDraft(text);
            setFeedback(null);
          }}
          placeholder="Escreva aqui"
          placeholderTextColor={palette.textTertiary}
          selectionColor={palette.accent}
          cursorColor={palette.accent}
          autoFocus
          autoCorrect={false}
          returnKeyType="send"
          submitBehavior="submit"
          onSubmitEditing={() => send(false)}
          style={styles.input}
        />
        {draft ? <IconButton icon="x" label="Limpar texto" onPress={() => setDraft('')} /> : null}
      </View>
      <View style={styles.meta}>
        <Txt variant="caption" tone={problem ? 'danger' : feedback ? (feedback.tone === 'success' ? 'success' : 'danger') : 'secondary'} style={styles.metaText}>
          {problem ?? feedback?.text ?? 'Revise antes de enviar: o texto é digitado exatamente como aparece aqui.'}
        </Txt>
        <Txt variant="caption" tone={bytes > MAX_BYTES ? 'danger' : 'tertiary'} tabular>
          {bytes}/{MAX_BYTES}
        </Txt>
      </View>
    </Sheet>
  );
}

const makeStyles = (palette: Palette) => ({
  field: {
    flexDirection: 'row' as const,
    alignItems: 'center' as const,
    borderWidth: 1.5,
    borderColor: palette.accent,
    borderRadius: radius.lg,
    backgroundColor: palette.surface,
    paddingLeft: space.lg,
  },
  input: {flex: 1, minHeight: 56, fontSize: 17, color: palette.text},
  meta: {flexDirection: 'row' as const, alignItems: 'flex-start' as const, gap: space.md},
  metaText: {flex: 1},
});
