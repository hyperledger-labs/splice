import * as React from 'react';
import BigNumber from 'bignumber.js';
import { AmountDisplay, TitledTable } from 'common-frontend';

import { TableBody, TableCell, TableHead, TableRow } from '@mui/material';

export const ValidatorLeaderboardTable: React.FC = () => {
  const validators = new Array(20).fill(1).map((_, i) => {
    return {
      name: 'SVS.cns',
      totalTransfers: BigNumber(12345.12345),
      totalRewards: BigNumber(12345.12345),
    };
  });
  return (
    <TitledTable title="Validator Leaderboard">
      <TableHead>
        <TableRow>
          <TableCell>Name</TableCell>
          <TableCell align="right">Total Transfers</TableCell>
          <TableCell align="right">Total Rewards</TableCell>
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

export default ValidatorLeaderboardTable;

const ValidatorRow: React.FC<{
  app: { name: string; totalTransfers: BigNumber; totalRewards: BigNumber };
}> = ({ app }) => {
  const { name, totalRewards, totalTransfers } = app;
  return (
    <TableRow>
      <TableCell>{name}</TableCell>
      <TableCell align="right">
        <AmountDisplay amount={totalRewards} currency="CC" />
      </TableCell>
      <TableCell align="right">
        <AmountDisplay amount={totalTransfers} currency="CC" />
      </TableCell>
    </TableRow>
  );
};
