// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { ContentCopy } from '@mui/icons-material';
import { Box, Chip, IconButton, Typography } from '@mui/material';

interface MemberIdentifierProps {
  partyId: string;
  isYou: boolean;
}

const MemberIdentifier: React.FC<MemberIdentifierProps> = ({ partyId, isYou }) => (
  <Box sx={{ display: 'flex', alignItems: 'center' }}>
    <Typography
      variant="body1"
      fontWeight="medium"
      fontFamily="Source Code Pro"
      fontSize="14px"
      data-testid="action-required-requester"
    >
      {partyId}
    </Typography>
    <IconButton color="secondary" onClick={() => navigator.clipboard.writeText(partyId)}>
      <ContentCopy sx={{ fontSize: '14px' }} />
    </IconButton>
    {isYou && <Chip label="You" size="small" data-testid="action-required-you" />}
  </Box>
);

export default MemberIdentifier;
