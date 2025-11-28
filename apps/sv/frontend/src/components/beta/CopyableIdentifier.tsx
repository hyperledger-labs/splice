// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { ContentCopy } from '@mui/icons-material';
import { Box, Chip, IconButton, Typography } from '@mui/material';

export type CopyableIdentifierSize = 'small' | 'large';

interface CopyableIdentifierProps {
  value: string;
  badge?: string;
  size: CopyableIdentifierSize;
  'data-testid': string;
}

const CopyableIdentifier: React.FC<CopyableIdentifierProps> = ({
  value,
  badge,
  size,
  'data-testid': testId,
}) => (
  <Box sx={{ display: 'flex', alignItems: 'center', color: 'text.light' }} data-testid={testId}>
    <Typography
      variant="body1"
      fontWeight="medium"
      fontFamily="Source Code Pro"
      fontSize={size === 'small' ? '14px' : '18px'}
      data-testid={`${testId}-value`}
      sx={{ maxWidth: '100%', textOverflow: 'ellipsis', overflow: 'hidden' }}
    >
      {value}
    </Typography>
    <IconButton
      color="secondary"
      data-testid={`${testId}-copy-button`}
      onClick={() => navigator.clipboard.writeText(value)}
    >
      <ContentCopy sx={{ fontSize: size === 'small' ? '14px' : '18px' }} />
    </IconButton>
    {badge !== undefined && <Chip label={badge} size="small" data-testid={`${testId}-badges`} />}
  </Box>
);

export default CopyableIdentifier;
