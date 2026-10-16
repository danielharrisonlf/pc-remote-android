import React, {Fragment, type ReactNode} from 'react';
import {Linking, View} from 'react-native';
import {parseMarkdown, type Inline} from '../../domain/markdown';
import {usePalette, useStyles} from '../../theme/ThemeProvider';
import {space, type Palette} from '../../theme/tokens';
import {Txt} from './Txt';

/** Release notes as native text. Only GitHub links are tappable. */
export function Markdown({source}: {source: string}) {
  const palette = usePalette();
  const styles = useStyles(makeStyles);

  const inline = (nodes: Inline[]): ReactNode =>
    nodes.map((node, index) => {
      switch (node.type) {
        case 'text':
          return <Fragment key={index}>{node.text}</Fragment>;
        case 'strong':
          return (
            <Txt key={index} variant="bodyStrong">
              {inline(node.children)}
            </Txt>
          );
        case 'em':
          return (
            <Txt key={index} style={styles.em}>
              {inline(node.children)}
            </Txt>
          );
        case 'code':
          return (
            <Txt key={index} variant="mono" style={[styles.code, {backgroundColor: palette.surfaceSunken}]}>
              {node.text}
            </Txt>
          );
        case 'link':
          return node.href.startsWith('https://github.com/') ? (
            <Txt key={index} tone="accent" style={styles.link} accessibilityRole="link" onPress={() => Linking.openURL(node.href).catch(() => undefined)}>
              {inline(node.children)}
            </Txt>
          ) : (
            <Fragment key={index}>{inline(node.children)}</Fragment>
          );
      }
    });

  const blocks = parseMarkdown(source);
  if (!blocks.length) {
    return <Txt tone="secondary">Esta versão não tem notas publicadas.</Txt>;
  }
  return (
    <View style={styles.root}>
      {blocks.map((block, index) => {
        switch (block.type) {
          case 'heading':
            return (
              <Txt key={index} variant={block.level === 1 ? 'title' : 'bodyStrong'} accessibilityRole="header" style={styles.heading}>
                {inline(block.children)}
              </Txt>
            );
          case 'paragraph':
            return <Txt key={index}>{inline(block.children)}</Txt>;
          case 'rule':
            return <View key={index} style={styles.rule} />;
          case 'list':
            return (
              <View key={index} style={styles.list}>
                {block.items.map((item, itemIndex) => (
                  <View key={itemIndex} style={styles.item}>
                    <Txt tone="tertiary" style={styles.marker}>
                      {block.ordered ? `${itemIndex + 1}.` : '•'}
                    </Txt>
                    <Txt style={styles.itemText}>{inline(item)}</Txt>
                  </View>
                ))}
              </View>
            );
        }
      })}
    </View>
  );
}

const makeStyles = (palette: Palette) => ({
  root: {gap: space.md},
  heading: {marginTop: space.xs},
  em: {fontStyle: 'italic' as const},
  code: {fontSize: 13},
  link: {textDecorationLine: 'underline' as const},
  rule: {height: 1, backgroundColor: palette.border},
  list: {gap: space.sm},
  item: {flexDirection: 'row' as const, gap: space.sm},
  marker: {width: 18},
  itemText: {flex: 1},
});
