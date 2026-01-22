// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { ContentCopy } from '@mui/icons-material';
import { Box, Chip, IconButton, Typography } from '@mui/material';

export type CopyableIdentifierSize = 'small' | 'large';

interface CopyableIdentifierProps {
  value: string;
  copyValue?: string;
  badge?: string;
  size: CopyableIdentifierSize;
  hideCopyButton?: boolean;
  'data-testid': string;
}

const CopyableIdentifier: React.FC<CopyableIdentifierProps> = ({
  value,
  copyValue,
  badge,
  size,
  hideCopyButton,
  'data-testid': testId,
}) => (
  <Box
    sx={{ display: 'flex', alignItems: 'center', color: 'text.light', minWidth: 0 }}
    data-testid={testId}
  >
    <Typography
      variant="body1"
      fontWeight="medium"
      fontFamily="Source Code Pro"
      fontSize={size === 'small' ? '14px' : '18px'}
      data-testid={`${testId}-value`}
      sx={{ textOverflow: 'ellipsis', overflow: 'hidden', whiteSpace: 'nowrap' }}
    >
      {value}
    </Typography>
    {!hideCopyButton && (
      <IconButton
        color="secondary"
        data-testid={`${testId}-copy-button`}
        onClick={e => {
          e.stopPropagation();
          e.preventDefault();
          navigator.clipboard.writeText(copyValue ?? value);
        }}
      >
        <ContentCopy sx={{ fontSize: size === 'small' ? '14px' : '18px' }} />
      </IconButton>
    )}
    {badge !== undefined && <Chip label={badge} size="small" data-testid={`${testId}-badge`} />}
  </Box>
);

export default CopyableIdentifier;
