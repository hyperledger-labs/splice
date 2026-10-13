// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import type { SxProps, Theme } from '@mui/material';

const hiddenScrollbarSx = {
  scrollbarWidth: 'none',
  msOverflowStyle: 'none',
  '&::-webkit-scrollbar': {
    display: 'none',
  },
} as const;

export const scrollContainerSx: SxProps<Theme> = {
  minWidth: 0,
  overflowX: 'auto',
  overflowY: 'hidden',
  ...hiddenScrollbarSx,
};

export const scrollTextSx: SxProps<Theme> = {
  display: 'inline-block',
  whiteSpace: 'nowrap',
  textOverflow: 'clip',
  // Intrinsic width for overflow scroll; parent must clip (minmax(0,1fr) / overflow).
  width: 'max-content',
};

export const ellipsisContainerSx: SxProps<Theme> = {
  minWidth: 0,
  // Figma truncated ID text slot (e.g. Vote proposal contract id Group 461): 270px
  maxWidth: 270,
  width: '100%',
  overflow: 'hidden',
};

/** Figma Group 461 truncated ID text width — also used to cap scrollable compact IDs. */
export const IDENTIFIER_COMPACT_MAX_WIDTH_PX = 270;

/** Figma Supporting URL value slot (`552:960`). */
export const URL_COMPACT_MAX_WIDTH_PX = 346;

export const ellipsisTextSx: SxProps<Theme> = {
  display: 'block',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  maxWidth: '100%',
};

export const scrollableIdentifierFieldSx: SxProps<Theme> = {
  fontFamily: 'Source Code Pro, monospace',
  display: 'inline-block',
  width: 'max-content',
  minWidth: '100%',
  maxWidth: '100%',
  whiteSpace: 'nowrap',
  textOverflow: 'clip',
};

const scrollableInputTextSx = {
  fontFamily: 'Source Code Pro, monospace',
  overflowX: 'auto',
  textOverflow: 'clip',
  whiteSpace: 'nowrap',
  ...hiddenScrollbarSx,
} as const;

export const scrollableTextFieldSx: SxProps<Theme> = {
  '& .MuiOutlinedInput-input': scrollableInputTextSx,
};

export const scrollableSelectFieldSx: SxProps<Theme> = {
  '& .MuiSelect-select': {
    ...scrollableInputTextSx,
    display: 'block',
    width: '100%',
    maxWidth: '100%',
  },
};

export const scrollTrackSx: SxProps<Theme> = {
  height: 0,
  opacity: 0,
  overflow: 'hidden',
  mt: 0,
  borderRadius: 1,
  bgcolor: 'rgba(255, 255, 255, 0.12)',
  position: 'relative',
  flexShrink: 0,
  transition: 'opacity 0.15s ease, height 0.15s ease, margin-top 0.15s ease',
  '.identifier-scroll-area:hover &': {
    height: 4,
    opacity: 1,
    mt: 0.5,
    bgcolor: 'rgba(255, 255, 255, 0.18)',
  },
};

export const scrollThumbSx = (
  thumbLeftPercent: number,
  thumbWidthPercent: number
): SxProps<Theme> => ({
  position: 'absolute',
  top: 0,
  bottom: 0,
  left: `${thumbLeftPercent}%`,
  width: `${thumbWidthPercent}%`,
  borderRadius: 1,
  bgcolor: 'rgba(255, 255, 255, 0.35)',
  transition: 'background-color 0.15s ease',
  '.identifier-scroll-area:hover &': {
    bgcolor: 'rgba(255, 255, 255, 0.72)',
  },
});

export const partyIdScrollGlobalStyles = {
  '.party-id': {
    position: 'relative',
    minWidth: 0,
    maxWidth: '100%',
  },
  '.party-id .MuiInputBase-root': {
    overflow: 'visible !important',
    minWidth: 0,
    maxWidth: '100%',
    width: '100%',
  },
  '.party-id .MuiInputBase-input.Mui-disabled': {
    overflowX: 'auto',
    textOverflow: 'clip !important',
    whiteSpace: 'nowrap',
    scrollbarWidth: 'none',
    msOverflowStyle: 'none',
  },
  '.party-id .MuiInputBase-input.Mui-disabled::-webkit-scrollbar': {
    display: 'none',
  },
  '.party-id-scroll-track': {
    position: 'absolute',
    left: 0,
    right: '40px',
    bottom: 0,
    height: 0,
    opacity: 0,
    overflow: 'hidden',
    borderRadius: '4px',
    backgroundColor: 'rgba(255, 255, 255, 0.12)',
    pointerEvents: 'none',
    transition: 'opacity 0.15s ease, height 0.15s ease',
  },
  '.party-id.identifier-scroll-area:hover .party-id-scroll-track': {
    height: '4px',
    opacity: 1,
    backgroundColor: 'rgba(255, 255, 255, 0.18)',
  },
  '.party-id-scroll-thumb': {
    position: 'absolute',
    top: 0,
    bottom: 0,
    left: 0,
    width: '100%',
    transformOrigin: 'left center',
    borderRadius: '4px',
    backgroundColor: 'rgba(255, 255, 255, 0.35)',
    transition: 'background-color 0.15s ease',
  },
  '.party-id.identifier-scroll-area:hover .party-id-scroll-thumb': {
    backgroundColor: 'rgba(255, 255, 255, 0.72)',
  },
} as const;
