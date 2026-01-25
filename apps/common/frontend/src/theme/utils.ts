// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
export const generateHslPalette = (
  hue: number,
  saturation: number,
  levels: number[]
): Record<string, string> =>
  levels
    .map(lightness => ({
      lightness,
      color: `hsl(${hue}, ${saturation}%, ${lightness}%)`,
    }))
    .reduce(
      (prev, { lightness, color }) => ({
        ...prev,
        [lightness]: color,
      }),
      {}
    );

export const generateRemValue = (stepSize: number, multiplier: number): string =>
  `${multiplier ** stepSize}rem`;

interface PillButtonConfig {
  props?: Record<string, string>;

  bgColor?: string;
  bgHoverColor?: string;
  bgFocusColor?: string;
  bgDisableColor: string;

  textColor: string;
  textHoverColor?: string;
  textFocusColor?: string;

  border?: string;
  borderFocus?: string;
  borderDisableColor?: string;
}

export const stylePillButton = (
  config: PillButtonConfig,
  additionalStyles?: Record<string, string | number>
): Record<string, unknown> => {
  const {
    props,
    bgColor = 'none',
    bgHoverColor = bgColor,
    bgFocusColor = bgHoverColor,
    bgDisableColor,
    textColor,
    textHoverColor = textColor,
    textFocusColor = textColor,
    border = 'none',
    borderFocus = border,
    borderDisableColor = bgDisableColor,
  } = config;

  return {
    props: { variant: 'pill', ...props },
    style: {
      borderRadius: 9999,
      backgroundColor: bgColor,
      border: border,
      color: textColor,
      ...additionalStyles,

      '&:hover': {
        backgroundColor: bgHoverColor,
        color: textHoverColor,
      },
      '&:focus-visible': {
        color: textFocusColor,
        backgroundColor: bgFocusColor,
        border: borderFocus,
      },
      '&:disabled': {
        backgroundColor: bgDisableColor,
        border: `2px solid ${borderDisableColor}`,
      },
    },
  };
};
