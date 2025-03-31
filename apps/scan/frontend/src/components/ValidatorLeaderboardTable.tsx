// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import {
  AmountDisplay,
  ErrorDisplay,
  Loading,
  PartyId,
  TitledTable,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { useGetTopValidatorsByValidatorRewards } from '@lfdecentralizedtrust/splice-common-frontend/scan-api';
import BigNumber from 'bignumber.js';

import { Stack, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';

import { useScanConfig } from '../utils';

export const ValidatorLeaderboardTable: React.FC = () => {
  const topValidatorsQuery = useGetTopValidatorsByValidatorRewards();

  switch (topValidatorsQuery.status) {
    case 'loading':
      return <Loading />;
    case 'error':
      return <ErrorDisplay message={'Could not retrieve validator leaderboard'} />;
    case 'success':
      const topValidators = topValidatorsQuery.data.validatorsAndRewards.map(validator => ({
        name: validator.provider,
        // TODO(#5280) - add transfer totals to API response
        totalRewards: BigNumber(validator.rewards),
      }));

      return topValidators.length === 0 ? (
        <Stack spacing={0} alignItems={'center'} marginTop={3}>
          <Typography variant="h6" fontWeight="bold">
            No Validator Activity Yet
          </Typography>
        </Stack>
      ) : (
        <TitledTable title="Validator Leaderboard">
          <TableHead>
            <TableRow>
              <TableCell>Name</TableCell>
              <TableCell align="right">Total Rewards</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {topValidators.map(validator => {
              return <ValidatorRow key={validator.name} {...validator} />;
            })}
          </TableBody>
        </TitledTable>
      );
  }
};

export default ValidatorLeaderboardTable;

const ValidatorRow: React.FC<{
  name: string;
  totalRewards: BigNumber;
}> = ({ name, totalRewards }) => {
  const config = useScanConfig();
  const amuletNameAcronym = config.spliceInstanceNames.amuletNameAcronym;

  return (
    <TableRow
      className="validator-leaderboard-row"
      data-selenium-text={`${name} ${totalRewards} ${amuletNameAcronym}`}
    >
      <TableCell>
        <PartyId partyId={name} />
      </TableCell>
      <TableCell align="right">
        <AmountDisplay amount={totalRewards} currency="AmuletUnit" />
      </TableCell>
    </TableRow>
  );
};
