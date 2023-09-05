import * as React from 'react';
import BigNumber from 'bignumber.js';
import { AmountDisplay, TitledTable, Loading, ErrorDisplay } from 'common-frontend';
import { PartyId } from 'common-frontend';
import { useRecentActivity } from 'common-frontend/scan-api';

import { TableBody, TableCell, TableHead, TableRow } from '@mui/material';
import Typography from '@mui/material/Typography';

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
  activity: {
    provider: string;
    sender: string;
    receivers?: string[];
    amount: string;
    coinPrice: string;
  };
}> = ({ activity }) => {
  const { amount, provider, receivers, sender, coinPrice } = activity;
  const someReceivers = receivers || [];
  let receiver;

  if (someReceivers.length === 0) {
    receiver = (
      <Typography className="receiver" data-selenium-text="Automation" variant="body1">
        Automation
      </Typography>
    );
  } else if (someReceivers.length === 1) {
    receiver = <PartyId className="receiver" partyId={someReceivers[0]} />;
  } else {
    receiver = (
      <Typography className="receiver" data-selenium-text="Multiple Recipients" variant="body1">
        Multiple Recipients
      </Typography>
    );
  }

  return (
    <TableRow>
      <TableCell>
        <PartyId partyId={provider} />
      </TableCell>
      <TableCell>
        <PartyId partyId={sender} />
      </TableCell>
      <TableCell>{receiver}</TableCell>
      <TableCell align="right">
        <AmountDisplay amount={amount} currency="CC" />
      </TableCell>
      <TableCell align="right">
        <AmountDisplay
          amount={amount}
          currency="CC"
          convert="CCtoUSD"
          coinPrice={new BigNumber(coinPrice)}
        />
      </TableCell>
    </TableRow>
  );
};
