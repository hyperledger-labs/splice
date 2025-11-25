// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { ContentCopy } from '@mui/icons-material';
import { Box, Chip, IconButton, Typography } from '@mui/material';

interface MemberIdentifierProps {
  partyId: string;
  isYou: boolean;
  size: 'small' | 'large';
  'data-testid': string;
}

const MAX_CHARACTERS = 40;

const MemberIdentifier: React.FC<MemberIdentifierProps> = ({
  partyId,
  isYou,
  size,
  'data-testid': testId,
}) => (
  <Box sx={{ display: 'flex', alignItems: 'center', color: 'text.light' }} data-testid={testId}>
    <Typography
      variant="body1"
      fontWeight="medium"
      fontFamily="Source Code Pro"
      fontSize={size === 'small' ? '14px' : '18px'}
      data-testid={`${testId}-party-id`}
    >
      {partyId.length > MAX_CHARACTERS ? partyId.slice(0, MAX_CHARACTERS) + '...' : partyId}
    </Typography>
    <IconButton
      color="secondary"
      data-testid={`${testId}-copy-button`}
      onClick={() => navigator.clipboard.writeText(partyId)}
    >
      <ContentCopy sx={{ fontSize: size === 'small' ? '14px' : '18px' }} />
    </IconButton>
    {isYou && <Chip label="You" size="small" data-testid={`${testId}-you`} />}
  </Box>
);

export default MemberIdentifier;
