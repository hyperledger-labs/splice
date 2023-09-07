import * as React from 'react';
import BigNumber from 'bignumber.js';
import { AmountDisplay, ErrorDisplay, Loading, PartyId, TitledTable } from 'common-frontend';
import { useRecentActivity } from 'common-frontend/scan-api';
import { ListRecentActivityResponseItem } from 'scan-openapi';

import { Button, Stack, TableBody, TableCell, TableHead, TableRow } from '@mui/material';
import Typography from '@mui/material/Typography';

export const RecentActivityTable: React.FC = () => {
  const recentActivityQuery = useRecentActivity();
  const hasNoActivities = (pagedActivities: ListRecentActivityResponseItem[][]): boolean => {
    return (
      pagedActivities === undefined ||
      pagedActivities.length === 0 ||
      pagedActivities.every(p => p === undefined || p.length === 0)
    );
  };
  const isLoading = recentActivityQuery.isLoading;
  const isError = recentActivityQuery.isError;

  const pagedActivities = recentActivityQuery.data ? recentActivityQuery.data.pages : [];

  return (
    <Stack spacing={4} direction="column">
      {isLoading ? (
        <Loading />
      ) : isError ? (
        <ErrorDisplay message={'Error while fetching recent activity.'} />
      ) : hasNoActivities(pagedActivities) ? (
        <Typography variant="h6">No recent activity available yet</Typography>
      ) : (
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
            {pagedActivities.map(
              activities =>
                activities &&
                activities.map(activity => (
                  <ActivityRow key={'activity-' + activity.eventId} activity={activity} />
                ))
            )}
          </TableBody>
        </TitledTable>
      )}
      <ViewMoreButton
        label={
          recentActivityQuery.isFetchingNextPage
            ? 'Loading more...'
            : recentActivityQuery.hasNextPage
            ? 'Load More'
            : 'Nothing more to load'
        }
        loadMore={() => recentActivityQuery.fetchNextPage()}
        disabled={!recentActivityQuery.hasNextPage}
      />
    </Stack>
  );
};

interface ViewMoreButtonProps {
  loadMore: () => void;
  label: string;
  disabled: boolean;
}

const ViewMoreButton: React.FC<ViewMoreButtonProps> = ({ loadMore, label, disabled = false }) => {
  return (
    <Button
      id="view-more-transactions"
      variant="outlined"
      size="small"
      color="secondary"
      onClick={loadMore}
      disabled={disabled}
    >
      {label}
    </Button>
  );
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
