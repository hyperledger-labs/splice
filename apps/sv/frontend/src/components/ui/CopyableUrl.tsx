// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { ContentCopy } from '@mui/icons-material';
import { Box, IconButton, Link } from '@mui/material';
import { sanitizeUrl } from '@canton-network/splice-common-frontend-utils';
import { useRef } from 'react';

import { useHorizontalScrollMetrics } from '../../hooks/useHorizontalScrollMetrics';
import type { CopyableIdentifierSize } from './CopyableIdentifier';
import {
  scrollContainerSx,
  scrollThumbSx,
  scrollTrackSx,
  URL_COMPACT_MAX_WIDTH_PX,
} from './identifierStyles';

interface CopyableUrlProps {
  url: string;
  size: CopyableIdentifierSize;
  /**
   * Fill the parent width (proposal-details section / Votes row). Default keeps
   * the compact Supporting URL slot (~346px).
   */
  fullWidth?: boolean;
  /** When true, only the (scrollable) link is rendered — caller places copy. */
  hideCopy?: boolean;
  'data-testid': string;
}

const CopyableUrl: React.FC<CopyableUrlProps> = ({
  url,
  size,
  fullWidth = false,
  hideCopy = false,
  'data-testid': testId,
}) => {
  const sanitizedUrl = sanitizeUrl(url);
  const fontSize = size === 'small' ? '14px' : '16px';
  const scrollRef = useRef<HTMLDivElement>(null);
  const metrics = useHorizontalScrollMetrics(scrollRef, [sanitizedUrl, fullWidth, hideCopy]);
  const textMaxWidth = fullWidth ? '100%' : URL_COMPACT_MAX_WIDTH_PX;
  const showCopy = !hideCopy;

  return (
    <Box
      className="identifier-scroll-area"
      sx={{
        display: fullWidth ? (showCopy ? 'flex' : 'block') : showCopy ? 'inline-flex' : 'block',
        alignItems: 'center',
        color: 'text.light',
        maxWidth: '100%',
        minWidth: 0,
        width: fullWidth || hideCopy ? '100%' : undefined,
        overflow: fullWidth || hideCopy ? 'hidden' : undefined,
      }}
      data-testid={testId}
    >
      <Box
        sx={{
          flex: fullWidth && showCopy ? '1 1 0%' : showCopy ? '0 1 auto' : undefined,
          minWidth: 0,
          maxWidth: textMaxWidth,
          width: fullWidth || hideCopy ? '100%' : undefined,
          display: 'flex',
          flexDirection: 'column',
          position: 'relative',
        }}
      >
        <Box
          ref={scrollRef}
          sx={{
            ...scrollContainerSx,
            maxWidth: textMaxWidth,
            width: '100%',
            ...(fullWidth || hideCopy ? { minWidth: 0 } : {}),
          }}
          data-testid={`${testId}-scroll`}
        >
          <Link
            href={sanitizedUrl}
            target="_blank"
            color="inherit"
            underline="hover"
            title={sanitizedUrl}
            sx={{
              fontFamily: 'Source Code Pro, monospace',
              fontSize,
              fontWeight: 'medium',
              display: 'inline-block',
              width: 'max-content',
              maxWidth: '100%',
              whiteSpace: 'nowrap',
            }}
            data-testid={`${testId}-link`}
          >
            {sanitizedUrl}
          </Link>
        </Box>
        {metrics.canScroll && (
          <Box sx={scrollTrackSx} data-testid={`${testId}-scroll-track`} aria-hidden>
            <Box sx={scrollThumbSx(metrics.thumbLeftPercent, metrics.thumbWidthPercent)} />
          </Box>
        )}
      </Box>
      {showCopy && (
        <IconButton
          color="secondary"
          data-testid={`${testId}-copy-button`}
          sx={{ flexShrink: 0 }}
          onClick={() => navigator.clipboard.writeText(sanitizedUrl)}
        >
          <ContentCopy sx={{ fontSize }} />
        </IconButton>
      )}
    </Box>
  );
};

export default CopyableUrl;
