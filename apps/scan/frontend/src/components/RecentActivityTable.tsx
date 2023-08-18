import * as React from 'react';
import BigNumber from 'bignumber.js';
import { AmountDisplay, TitledTable, Loading, ErrorDisplay } from 'common-frontend';
import { useRecentActivity } from 'common-frontend/scan-api';

import { TableBody, TableCell, TableHead, TableRow } from '@mui/material';

export const RecentActivityTable: React.FC = () => {
  const recentActivityQuery = useRecentActivity();
  switch (recentActivityQuery.status) {
    case 'loading':
      return <Loading />;
    case 'error':
      return <ErrorDisplay message={'Could not retrieve recent activity'} />;
    case 'success':
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
            {recentActivityQuery.data.activities.map((activity, index) => {
              return <ActivityRow key={'activity-' + index} activity={activity} />;
            })}
          </TableBody>
        </TitledTable>
      );
  }
};

export default RecentActivityTable;

const ActivityRow: React.FC<{
  activity: { provider: string; sender: string; receiver: string; amount: string; price: string };
}> = ({ activity }) => {
  const { amount, provider, receiver, sender, price } = activity;
  return (
    <TableRow>
      <TableCell>{provider}</TableCell>
      <TableCell>{sender}</TableCell>
      <TableCell>{receiver}</TableCell>
      <TableCell align="right">
        <AmountDisplay amount={amount} currency="CC" />
      </TableCell>
      <TableCell align="right">
        <AmountDisplay
          amount={amount}
          currency="CC"
          convert="CCtoUSD"
          coinPrice={new BigNumber(price)}
        />
      </TableCell>
    </TableRow>
  );
};
