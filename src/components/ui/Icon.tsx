import React, {memo} from 'react';
import Svg, {Circle, Ellipse, Line, Path, Polygon, Polyline, Rect} from 'react-native-svg';
import {iconData, type IconName} from './iconData';

const elements = {path: Path, circle: Circle, rect: Rect, line: Line, polyline: Polyline, polygon: Polygon, ellipse: Ellipse};

type IconProps = {name: IconName; size?: number; color: string; strokeWidth?: number};

/** Lucide-geometry icon: 24×24 grid, round caps, one stroke weight everywhere. */
export const Icon = memo(function IconView({name, size = 24, color, strokeWidth = 2}: IconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round">
      {iconData[name].map(([tag, attributes], index) => {
        const Element = elements[tag] as unknown as React.ComponentType<Record<string, unknown>>;
        return <Element key={index} {...attributes} />;
      })}
    </Svg>
  );
});

export type {IconName};
