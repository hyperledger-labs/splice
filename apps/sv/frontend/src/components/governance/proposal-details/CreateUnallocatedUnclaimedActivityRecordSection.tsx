// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { dateTimeFormatISO } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { Box, Typography } from '@mui/material';
import dayjs from 'dayjs';
import { DetailItem } from './DetailItem';
import { MemberIdentifier } from '../../beta';

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
      id="proposal-details-unallocated-unclaimed-activity-record-section"
      data-testid="proposal-details-unallocated-unclaimed-activity-record-section"
      sx={{ display: 'contents' }}
    >
      <DetailItem
        label="Beneficiary"
        value={
          <MemberIdentifier
            partyId={beneficiary}
            isYou={false}
            size="large"
            data-testid="proposal-details-beneficiary"
          />
        }
      />

      <DetailItem
        label="Amount"
        value={amount}
        labelId="proposal-details-amount-label"
        valueId="proposal-details-amount-value"
      />

      <DetailItem
        label="Must Mint Before"
        value={
          <>
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
          </>
        }
      />
    </Box>
  );
};
