import * as React from 'react';
import BigNumber from 'bignumber.js';
import { AmountDisplay, ErrorDisplay, Loading, TitledTable } from 'common-frontend';
import { useTopAppProviders } from 'common-frontend/scan-api';

import { Stack, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';

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
        totalTransfers: undefined, // TODO(#5280) - add transfer totals to API response
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
              <TableCell align="right">Total Transfers</TableCell>
              <TableCell align="right">Total Rewards</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {appProviders.map((app, index) => {
              return <AppRow key={'app-' + index} {...app} />;
            })}
          </TableBody>
        </TitledTable>
      );
  }
};

export default AppLeaderboardTable;

const AppRow: React.FC<{
  name: string;
  totalTransfers?: BigNumber;
  totalRewards: BigNumber;
}> = ({ name, totalRewards, totalTransfers }) => {
  return (
    <TableRow className="app-leaderboard-row">
      <TableCell>{name}</TableCell>
      <TableCell align="right">
        <AmountDisplay amount={totalTransfers} currency="CC" />
      </TableCell>
      <TableCell align="right">
        <AmountDisplay amount={totalRewards} currency="CC" />
      </TableCell>
    </TableRow>
  );
};
