import {StyleSheet} from 'react-native';

export const light = {bg: '#f6f7fb', surface: '#ffffff', ink: '#15233a', soft: '#63728a', line: '#e4e9f0', accent: '#245ae8', accentPale: '#eaf0ff', good: '#24845b', warn: '#a65a1d'};
export const dark = {bg: '#0d1421', surface: '#172233', ink: '#f5f7fc', soft: '#a7b5cc', line: '#2d3b50', accent: '#7da0ff', accentPale: '#26375d', good: '#75d2a0', warn: '#ffb77b'};
export type Palette = typeof light;

export const styles = StyleSheet.create({
  page: {flex: 1}, content: {paddingHorizontal: 20, paddingTop: 20, paddingBottom: 40, gap: 16},
  header: {flexDirection: 'row', alignItems: 'flex-start', marginBottom: 4}, eyebrow: {fontWeight: '800', letterSpacing: 2, fontSize: 12}, heading: {fontSize: 31, fontWeight: '700', marginTop: 6}, status: {fontSize: 14, marginTop: 5, textTransform: 'capitalize'}, iconButton: {width: 48, height: 48, borderRadius: 16, alignItems: 'center', justifyContent: 'center'},
  card: {borderWidth: 1, borderRadius: 24, padding: 20, gap: 8}, cardTitle: {fontSize: 20, fontWeight: '700', marginBottom: 8}, body: {fontSize: 14, lineHeight: 21}, label: {fontSize: 15, fontWeight: '600'}, hint: {fontSize: 12, lineHeight: 17, marginTop: 5}, notice: {padding: 14, borderRadius: 12},
  volumeHeader: {flexDirection: 'row', alignItems: 'baseline', justifyContent: 'space-between', gap: 12}, volumeValue: {fontSize: 48, fontWeight: '800'}, slider: {width: '100%', height: 56}, row: {flexDirection: 'row', gap: 9, marginTop: 8}, button: {minHeight: 52, borderRadius: 14, justifyContent: 'center', alignItems: 'center', paddingHorizontal: 9, paddingVertical: 8}, buttonText: {fontSize: 14, fontWeight: '700', textAlign: 'center'},
  mediaTitle: {fontSize: 19, fontWeight: '700'}, clipText: {padding: 14, borderRadius: 12, fontSize: 14, minHeight: 62, lineHeight: 21}, setting: {flexDirection: 'row', gap: 10, alignItems: 'center', paddingVertical: 12, borderBottomWidth: 1}, fieldLabel: {fontSize: 13, fontWeight: '600'}, input: {minHeight: 50, borderWidth: 1, borderRadius: 12, marginTop: 5, paddingHorizontal: 12, fontSize: 15}, choice: {borderWidth: 1, borderRadius: 12, paddingHorizontal: 12, paddingVertical: 10, marginTop: 6},
});
