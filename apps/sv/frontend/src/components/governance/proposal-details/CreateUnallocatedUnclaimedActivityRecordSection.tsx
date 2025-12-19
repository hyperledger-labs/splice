// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { dateTimeFormatISO } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { Box, Divider, Typography } from '@mui/material';
import dayjs from 'dayjs';
import { DetailItem } from './DetailItem';
import { PartyId } from '@lfdecentralizedtrust/splice-common-frontend';

interface CreateUnallocatedUnclaimedActivityRecordSectionProps {
  beneficiary: string;
  amount: string;
  mintBefore: string;
}

export const CreateUnallocatedUnclaimedActivityRecordSection: React.FC<
  CreateUnallocatedUnclaimedActivityRecordSectionProps
> = props => {
  const { beneficiary, amount, mintBefore } = props;

  return (
    <Box
      sx={{ py: 1 }}
      id="proposal-details-unallocated-unclaimed-activity-record-section"
      data-testid="proposal-details-unallocated-unclaimed-activity-record-section"
    >
      <Box sx={{ display: 'flex', flexDirection: 'column' }}>
        <PartyId partyId={beneficiary} id="proposal-details-beneficiary" />
        <Divider sx={{ my: 1 }} />

        <DetailItem
          label="Amount"
          value={amount}
          labelId="proposal-details-amount-label"
          valueId="proposal-details-amount-value"
        />

        <Box sx={{ py: 1 }}>
          <Typography variant="subtitle2" color="text.secondary" gutterBottom>
            Must Mint Before
          </Typography>

          <Typography
            variant="body1"
            data-testid="proposal-details-must-mint-before-value"
            gutterBottom
          >
            {dayjs(mintBefore).format(dateTimeFormatISO)}
          </Typography>

          <Typography variant="body2" color="text.secondary">
            {dayjs(mintBefore).fromNow()}
          </Typography>
        </Box>
      </Box>
    </Box>
  );
};
