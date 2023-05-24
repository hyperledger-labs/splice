import * as React from 'react';
import BigNumber from 'bignumber.js';
import { AmountDisplay, DateDisplay, TitledTable } from 'common-frontend';

import { TableBody, TableCell, TableHead, TableRow } from '@mui/material';

export const DomainFeesLeaderboardTable: React.FC = () => {
  const validators = new Array(20).fill(1).map((_, i) => {
    return {
      name: 'SVS.cns',
      numPurchases: 10,
      totalTrafficPurchased: 123456,
      totalCcSpent: BigNumber(12345.12345),
      totalUsdSpent: BigNumber(12345.12345),
      lastPurchasedAt: '2023-01-01T11:11:11Z',
    };
  });
  return (
    <TitledTable title="Domain Fees Leaderboard">
      <TableHead>
        <TableRow>
          <TableCell>Name</TableCell>
          <TableCell align="right">Number of Purchases</TableCell>
          <TableCell align="right">Total Traffic Purchased</TableCell>
          <TableCell align="right">Total CC Spent</TableCell>
          <TableCell align="right">Total USD Spent</TableCell>
          <TableCell align="right">Last Purchased At</TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {validators.map((app, index) => {
          return <ValidatorRow key={'app-' + index} app={app} />;
        })}
      </TableBody>
    </TitledTable>
  );
};

export default DomainFeesLeaderboardTable;

const ValidatorRow: React.FC<{
  app: {
    name: string;
    numPurchases: number;
    totalTrafficPurchased: number;
    totalCcSpent: BigNumber;
    totalUsdSpent: BigNumber;
    lastPurchasedAt: string;
  };
}> = ({ app }) => {
  const {
    name,
    numPurchases,
    totalTrafficPurchased,
    totalCcSpent,
    totalUsdSpent,
    lastPurchasedAt,
  } = app;
  return (
    <TableRow>
      <TableCell>{name}</TableCell>
      <TableCell align="right">{numPurchases}</TableCell>
      <TableCell align="right">{totalTrafficPurchased}</TableCell>
      <TableCell align="right">
        <AmountDisplay amount={totalCcSpent} currency="CC" />
      </TableCell>
      <TableCell align="right">
        <AmountDisplay amount={totalUsdSpent} currency="USD" />
      </TableCell>
      <TableCell align="right">
        <DateDisplay datetime={lastPurchasedAt} />
      </TableCell>
    </TableRow>
  );
};
