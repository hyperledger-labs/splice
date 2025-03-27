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
import { useTopAppProviders } from '@lfdecentralizedtrust/splice-common-frontend/scan-api';
import BigNumber from 'bignumber.js';

import { Stack, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';

import { useScanConfig } from '../utils/config';

export const AppLeaderboardTable: React.FC = () => {
  const topAppProvidersQuery = useTopAppProviders();

  switch (topAppProvidersQuery.status) {
    case 'loading':
      return <Loading />;
    case 'error':
      return <ErrorDisplay message={'Could not retrieve app provider leaderboard'} />;
    case 'success':
      const appProviders = topAppProvidersQuery.data.providersAndRewards.map(provider => ({
        name: provider.provider,
        // TODO(#5280) - add transfer totals to API response
        totalRewards: BigNumber(provider.rewards),
      }));

      return appProviders.length === 0 ? (
        <Stack spacing={0} alignItems={'center'} marginTop={3}>
          <Typography variant="h6" fontWeight="bold">
            No App Provider Activity Yet
          </Typography>
        </Stack>
      ) : (
        <TitledTable title="App Leaderboard">
          <TableHead>
            <TableRow>
              <TableCell>Name</TableCell>
              <TableCell align="right">Total Rewards</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {appProviders.map(app => {
              return <AppRow key={app.name} {...app} />;
            })}
          </TableBody>
        </TitledTable>
      );
  }
};

export default AppLeaderboardTable;

const AppRow: React.FC<{
  name: string;
  totalRewards: BigNumber;
}> = ({ name, totalRewards }) => {
  const config = useScanConfig();
  const amuletNameAcronym = config.spliceInstanceNames.amuletNameAcronym;

  return (
    <TableRow
      className="app-leaderboard-row"
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
