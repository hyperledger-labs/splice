// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { PartyId } from '@lfdecentralizedtrust/splice-common-frontend';
import { Box, Typography } from '@mui/material';

interface DetailItemProps {
  label: string;
  value: React.ReactNode;
  labelId?: string;
  valueId?: string;
  isPartyId?: boolean;
}

export const DetailItem: React.FC<DetailItemProps> = props => {
  const { label, value, labelId, valueId, isPartyId } = props;

  return (
    <Box sx={{ py: 1 }}>
      <Typography
        variant="subtitle2"
        color="text.secondary"
        id={labelId}
        data-testid={labelId}
        gutterBottom
      >
        {label}
      </Typography>
      {isPartyId ? (
        <PartyId partyId={`${value}`} id={valueId} data-testid={valueId} />
      ) : (
        <Typography variant="body1" id={valueId} data-testid={valueId}>
          {value}
        </Typography>
      )}
    </Box>
  );
};
