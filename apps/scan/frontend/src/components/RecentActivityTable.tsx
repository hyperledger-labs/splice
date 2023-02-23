import * as React from 'react';
import { AmountDisplay, TitledTable } from 'common-frontend';

import { TableBody, TableCell, TableHead, TableRow } from '@mui/material';

export const RecentActivityTable: React.FC = () => {
  const activities = new Array(10).fill(1).map((_, i) => {
    return { provider: 'SVS.cns', sender: 'Bank.cns', receiver: 'Repo.cns', amount: i + 1 };
  });
  return (
    <TitledTable title="Recent Activity">
      <TableHead>
        <TableRow>
          <TableCell>Provider</TableCell>
          <TableCell>Sender</TableCell>
          <TableCell>Receiver</TableCell>
          <TableCell align="right">Amount</TableCell>
          <TableCell align="right">Price</TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {activities.map((activity, index) => {
          return <ActivityRow key={'activity-' + index} activity={activity} />;
        })}
      </TableBody>
    </TitledTable>
  );
};

export default RecentActivityTable;

const ActivityRow: React.FC<{
  activity: { provider: string; sender: string; receiver: string; amount: number };
}> = ({ activity }) => {
  const { amount, provider, receiver, sender } = activity;
  const exchangeRateUSDToCC = 10; // 1 USD = 10 CC
  return (
    <TableRow>
      <TableCell>{provider}</TableCell>
      <TableCell>{sender}</TableCell>
      <TableCell>{receiver}</TableCell>
      <TableCell align="right">
        <AmountDisplay amount={amount.toString()} currency="CC" />
      </TableCell>
      <TableCell align="right">
        <AmountDisplay amount={(amount / exchangeRateUSDToCC).toString()} currency="USD" />
      </TableCell>
    </TableRow>
  );
};
