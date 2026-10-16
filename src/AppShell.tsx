import React, {useCallback, useEffect, useState} from 'react';
import {BackHandler, StatusBar, View} from 'react-native';
import {SafeAreaProvider, useSafeAreaInsets} from 'react-native-safe-area-context';
import {remote} from './communication/remote';
import {discovery} from './communication/discovery';
import {updates} from './communication/updates';
import {NAVIGATION_BAR_HEIGHT, NavigationBar} from './components/NavigationBar';
import {UpdateBanner, UpdateSheet} from './components/UpdateSheet';
import {IconButton} from './components/ui/Button';
import {StatusChip} from './components/ui/Bits';
import {SnackbarProvider, useSnackbar} from './components/ui/Snackbar';
import {Txt} from './components/ui/Txt';
import {useLatency, useRemoteValue, useUpdates} from './hooks/useRemote';
import {ClipboardScreen} from './screens/ClipboardScreen';
import {ConnectionScreen, statusPresentation} from './screens/ConnectionScreen';
import {MediaScreen} from './screens/MediaScreen';
import {TouchpadScreen} from './screens/TouchpadScreen';
import type {TabName} from './screens/tabs';
import {ThemeProvider, usePalette, useStyles} from './theme/ThemeProvider';
import {space, type Palette} from './theme/tokens';

export default function App() {
  return (
    <SafeAreaProvider>
      <Themed />
    </SafeAreaProvider>
  );
}

function Themed() {
  const theme = useRemoteValue(view => view.theme);
  const insets = useSafeAreaInsets();
  return (
    <ThemeProvider preference={theme}>
      <SnackbarProvider bottomOffset={NAVIGATION_BAR_HEIGHT + insets.bottom}>
        <Shell />
      </SnackbarProvider>
    </ThemeProvider>
  );
}

function Shell() {
  const palette = usePalette();
  const styles = useStyles(makeStyles);
  const insets = useSafeAreaInsets();
  const snackbar = useSnackbar();
  const ready = useRemoteValue(view => view.ready);
  const paired = useRemoteValue(view => Boolean(view.pcName));
  const pcName = useRemoteValue(view => view.pcName);
  const status = useRemoteValue(view => view.status);
  const foreground = useRemoteValue(view => view.foreground);
  const error = useRemoteValue(view => view.error);
  const theme = useRemoteValue(view => view.theme);
  const skipped = useRemoteValue(view => view.skippedUpdate);
  const update = useUpdates();
  const [tab, setTab] = useState<TabName | null>(null);
  const [updateOpen, setUpdateOpen] = useState(false);
  const connected = status === 'conectado';
  const latency = useLatency(connected && tab !== 'connection');
  const showUpdate = update.available && update.release !== null && update.release.version !== skipped;
  const home: TabName = paired ? 'touchpad' : 'connection';
  const activeTab = tab ?? home;

  useEffect(() => {
    remote.start();
    return updates.startAutomatic();
  }, []);

  // First screen: the touchpad when a PC is saved, otherwise pairing.
  useEffect(() => {
    if (ready && tab === null) {
      setTab(home);
    }
  }, [ready, tab, home]);

  // Errors from the connection layer become snackbars instead of inline banners.
  useEffect(() => {
    if (error) {
      snackbar.show(error, 'error');
      remote.clearError();
    }
  }, [error, snackbar]);

  // While a saved PC is offline, keep mDNS running: when it reappears on the
  // Wi-Fi, the native layer reconnects to it automatically.
  useEffect(() => {
    if (!paired || !foreground || connected || status === 'pareando' || status === 'aguardando aprovação') {
      return;
    }
    return discovery.acquire();
  }, [paired, foreground, connected, status]);

  // System Back returns to the start destination before leaving the app.
  useEffect(() => {
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      if (activeTab !== home) {
        setTab(home);
        return true;
      }
      return false;
    });
    return () => subscription.remove();
  }, [activeTab, home]);

  const navigate = useCallback((next: TabName) => setTab(next), []);
  const presentation = statusPresentation(status);

  return (
    <View style={styles.root}>
      <StatusBar barStyle={palette.scheme === 'dark' ? 'light-content' : 'dark-content'} />
      <View style={[styles.topBar, {paddingTop: insets.top + space.sm}]}>
        <View style={styles.titleBlock}>
          <Txt variant="title" numberOfLines={1}>
            {pcName || 'PC Remote'}
          </Txt>
          <View style={styles.statusRow}>
            <StatusChip tone={presentation.tone} label={presentation.label} />
            {connected && latency !== null ? (
              <Txt variant="caption" tone="tertiary" tabular>
                {latency} ms
              </Txt>
            ) : null}
          </View>
        </View>
        <IconButton
          icon={palette.scheme === 'dark' ? 'sun' : 'moon'}
          label={palette.scheme === 'dark' ? 'Usar tema claro' : 'Usar tema escuro'}
          onPress={() => remote.setTheme(palette.scheme === 'dark' ? 'light' : 'dark')}
          selected={theme !== 'system'}
        />
      </View>
      {showUpdate ? <UpdateBanner onOpen={() => setUpdateOpen(true)} /> : null}

      <View style={styles.body}>
        {!ready ? null : activeTab === 'touchpad' ? (
          <TouchpadScreen onNavigate={navigate} />
        ) : activeTab === 'media' ? (
          <MediaScreen onNavigate={navigate} />
        ) : activeTab === 'clipboard' ? (
          <ClipboardScreen />
        ) : (
          <ConnectionScreen onOpenUpdate={() => setUpdateOpen(true)} />
        )}
      </View>

      <NavigationBar active={activeTab} onChange={navigate} bottomInset={insets.bottom} badges={{connection: showUpdate}} />
      <UpdateSheet visible={updateOpen} onClose={() => setUpdateOpen(false)} />
    </View>
  );
}

const makeStyles = (palette: Palette) => ({
  root: {flex: 1, backgroundColor: palette.bg},
  topBar: {
    flexDirection: 'row' as const,
    alignItems: 'center' as const,
    gap: space.sm,
    paddingLeft: space.xl,
    paddingRight: space.sm,
    paddingBottom: space.md,
  },
  titleBlock: {flex: 1, gap: 6},
  statusRow: {flexDirection: 'row' as const, alignItems: 'center' as const, gap: space.sm},
  body: {flex: 1},
});
