// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { ContentCopy } from '@mui/icons-material';
import { Box, Chip, IconButton, Typography } from '@mui/material';
import { useRef } from 'react';

import { useHorizontalScrollMetrics } from '../../hooks/useHorizontalScrollMetrics';
import {
  ellipsisContainerSx,
  ellipsisTextSx,
  IDENTIFIER_COMPACT_MAX_WIDTH_PX,
  scrollContainerSx,
  scrollTextSx,
  scrollThumbSx,
  scrollTrackSx,
} from './identifierStyles';

export type CopyableIdentifierSize = 'small' | 'large';
export type CopyableIdentifierOverflow = 'scroll' | 'ellipsis';

interface CopyableIdentifierProps {
  value: string;
  copyValue?: string;
  badge?: string;
  size: CopyableIdentifierSize;
  overflow?: CopyableIdentifierOverflow;
  /**
   * Caps the text slot (Figma ~270px). With `overflow="scroll"`, the ID stays
   * horizontally scrollable inside the cap (#1785 + Figma width). With
   * `overflow="ellipsis"`, CSS ellipsis is used instead.
   */
  maxWidth?: number;
  /**
   * Fill the parent width: party-ID text flexes/scrolls; copy + badge stay fixed.
   * Used by Votes rows (ID + reason shrink; status stays right-aligned).
   */
  fullWidth?: boolean;
  /** When true, only the (scrollable) value is rendered — caller places copy / badge. */
  hideCopy?: boolean;
  'data-testid': string;
}

/** Gap between party-ID text and copy / You accessories. */
const IDENTIFIER_ACCESSORY_GAP = '8px';

const CopyableIdentifier: React.FC<CopyableIdentifierProps> = ({
  value,
  copyValue,
  badge,
  size,
  overflow = 'scroll',
  maxWidth,
  fullWidth = false,
  hideCopy = false,
  'data-testid': testId,
}) => {
  const scrollRef = useRef<HTMLDivElement>(null);
  const metrics = useHorizontalScrollMetrics(scrollRef, [value, maxWidth, fullWidth]);
  const fontSize = size === 'small' ? '14px' : '16px';
  const isEllipsis = overflow === 'ellipsis';
  const compactMaxWidth = maxWidth ?? (isEllipsis ? IDENTIFIER_COMPACT_MAX_WIDTH_PX : undefined);
  const showAccessories = !hideCopy;

  return (
    <Box
      className={isEllipsis ? undefined : 'identifier-scroll-area'}
      sx={{
        display: fullWidth
          ? showAccessories
            ? 'flex'
            : 'block'
          : showAccessories
            ? 'inline-flex'
            : 'block',
        alignItems: 'center',
        gap: showAccessories ? IDENTIFIER_ACCESSORY_GAP : 0,
        color: 'text.light',
        maxWidth: '100%',
        minWidth: 0,
        width: fullWidth || hideCopy ? '100%' : 'fit-content',
        overflow: fullWidth || hideCopy ? 'hidden' : undefined,
      }}
      data-testid={testId}
    >
      <Box
        sx={{
          flex: fullWidth ? '1 1 0%' : showAccessories ? '0 1 auto' : undefined,
          minWidth: 0,
          width: fullWidth || hideCopy ? '100%' : undefined,
          maxWidth: fullWidth || hideCopy ? '100%' : (compactMaxWidth ?? '100%'),
          overflow: 'hidden',
          display: 'flex',
          flexDirection: 'column',
          position: 'relative',
        }}
      >
        <Box
          ref={isEllipsis ? undefined : scrollRef}
          sx={
            isEllipsis
              ? ellipsisContainerSx
              : {
                  ...scrollContainerSx,
                  ...(fullWidth
                    ? { width: '100%', minWidth: 0 }
                    : compactMaxWidth !== undefined
                      ? { maxWidth: compactMaxWidth, width: '100%' }
                      : {}),
                }
          }
          data-testid={`${testId}-${isEllipsis ? 'ellipsis' : 'scroll'}`}
        >
          <Typography
            component="span"
            variant="body1"
            fontWeight="medium"
            fontFamily="Source Code Pro, monospace"
            fontSize={fontSize}
            title={value}
            data-testid={`${testId}-value`}
            sx={isEllipsis ? ellipsisTextSx : scrollTextSx}
          >
            {value}
          </Typography>
        </Box>
        {!isEllipsis && metrics.canScroll && (
          <Box sx={scrollTrackSx} data-testid={`${testId}-scroll-track`} aria-hidden>
            <Box sx={scrollThumbSx(metrics.thumbLeftPercent, metrics.thumbWidthPercent)} />
          </Box>
        )}
      </Box>
      {showAccessories && (
        <>
          <IconButton
            color="secondary"
            data-testid={`${testId}-copy-button`}
            sx={{ flexShrink: 0 }}
            onClick={e => {
              e.stopPropagation();
              e.preventDefault();
              navigator.clipboard.writeText(copyValue ?? value);
            }}
          >
            <ContentCopy sx={{ fontSize }} />
          </IconButton>
          {badge !== undefined && (
            <Chip
              label={badge}
              size="small"
              data-testid={`${testId}-badge`}
              sx={{ flexShrink: 0 }}
            />
          )}
        </>
      )}
    </Box>
  );
};

export default CopyableIdentifier;
