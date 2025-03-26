// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import {
  AmountDisplay,
  ErrorDisplay,
  Loading,
  TitledTable,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { useGetTopValidatorsByPurchasedTraffic } from '@lfdecentralizedtrust/splice-common-frontend/scan-api';
import BigNumber from 'bignumber.js';

import { Stack, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';

import { useScanConfig } from '../utils';

export const SynchronizerFeesLeaderboardTable: React.FC = () => {
  const topValidatorsQuery = useGetTopValidatorsByPurchasedTraffic();
  const config = useScanConfig();
  const amuletNameAcronym = config.spliceInstanceNames.amuletNameAcronym;

  switch (topValidatorsQuery.status) {
    case 'loading':
      return <Loading />;
    case 'error':
      return <ErrorDisplay message={'Could not retrieve validator leaderboard'} />;
    case 'success':
      const topValidators = topValidatorsQuery.data.validatorsByPurchasedTraffic.map(validator => ({
        name: validator.validator,
        numPurchases: validator.numPurchases,
        totalTrafficPurchased: validator.totalTrafficPurchased,
        totalCcSpent: BigNumber(validator.totalCcSpent),
        lastPurchasedInRound: validator.lastPurchasedInRound,
      }));

      return topValidators.length === 0 ? (
        <Stack spacing={0} alignItems={'center'} marginTop={3}>
          <Typography variant="h6" fontWeight="bold">
            No Validator Activity Yet
          </Typography>
        </Stack>
      ) : (
        <TitledTable title="Synchronizer Fees Leaderboard">
          <TableHead>
            <TableRow>
              <TableCell>Name</TableCell>
              <TableCell align="right">Number of Purchases</TableCell>
              <TableCell align="right">Total Traffic Purchased</TableCell>
              <TableCell align="right">Total {amuletNameAcronym} Spent</TableCell>
              <TableCell align="right">Last Purchased In Round</TableCell>
            </TableRow>
          </TableHead>

          <TableBody>
            {topValidators.map(validator => {
              return <ValidatorRow key={validator.name} validator={validator} />;
            })}
          </TableBody>
        </TitledTable>
      );
  }
};

export default SynchronizerFeesLeaderboardTable;

const ValidatorRow: React.FC<{
  validator: {
    name: string;
    numPurchases: number;
    totalTrafficPurchased: number;
    totalCcSpent: BigNumber;
    lastPurchasedInRound: number;
  };
}> = ({ validator }) => {
  const config = useScanConfig();
  const amuletNameAcronym = config.spliceInstanceNames.amuletNameAcronym;
  const { name, numPurchases, totalTrafficPurchased, totalCcSpent, lastPurchasedInRound } =
    validator;
  return (
    <TableRow
      className="synchronizer-fees-leaderboard-row"
      data-selenium-text={`${name} ${numPurchases} ${totalTrafficPurchased} ${totalCcSpent} ${amuletNameAcronym} ${lastPurchasedInRound}`}
    >
      <TableCell>{name}</TableCell>
      <TableCell align="right">{numPurchases}</TableCell>
      <TableCell align="right">{totalTrafficPurchased}</TableCell>
      <TableCell align="right">
        <AmountDisplay amount={totalCcSpent} currency="AmuletUnit" />
      </TableCell>
      <TableCell align="right">{lastPurchasedInRound}</TableCell>
    </TableRow>
  );
};
