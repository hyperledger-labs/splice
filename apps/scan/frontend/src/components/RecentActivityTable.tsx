import * as React from 'react';
import BigNumber from 'bignumber.js';
import {
  AmountDisplay,
  ErrorDisplay,
  Loading,
  PartyId,
  RateDisplay,
  TitledTable,
} from 'common-frontend';
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
              <TableCell>Type</TableCell>
              <TableCell>Provider</TableCell>
              <TableCell>Sender</TableCell>
              <TableCell>Receiver</TableCell>
              <TableCell align="right">Sender Balance Change</TableCell>
              <TableCell align="right">Total Receivers Balance Change</TableCell>
              <TableCell align="right">Price</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {pagedActivities.map(
              activities =>
                activities &&
                activities.map(activity => <ActivityRow key={activity.event_id} {...activity} />)
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

const ActivityRow: React.FC<ListRecentActivityResponseItem> = ({
  activity_type,
  provider,
  receivers,
  sender,
  coin_price,
}) => {
  let receiver;
  let receiversTotalAmount;
  if (receivers.length === 0) {
    receiver = <PartyId partyId={sender.party} />;
    receiversTotalAmount = BigNumber(0);
  } else if (receivers.length === 1) {
    const r = receivers[0];
    receiver = <PartyId className="receiver" partyId={r.party} />;
    receiversTotalAmount = r.amount;
  } else {
    receiversTotalAmount = receivers
      .map(p => BigNumber(p.amount))
      .reduce((prev, cur) => prev.plus(cur));
    receiver = (
      <Typography className="receiver" data-selenium-text="Multiple Receivers" variant="body1">
        Multiple Recipients
      </Typography>
    );
  }

  return (
    <TableRow>
      <TableCell>
        <Typography className="activity_type" variant="body1">
          {activity_type}
        </Typography>
      </TableCell>
      <TableCell>
        <PartyId partyId={provider} />
      </TableCell>
      <TableCell>
        <PartyId partyId={sender.party} />
      </TableCell>
      <TableCell>{receiver}</TableCell>
      <TableCell align="right">
        <RecentAmountDisplay amountCC={BigNumber(sender.amount)} />
      </TableCell>
      <TableCell align="right">
        <RecentAmountDisplay amountCC={BigNumber(receiversTotalAmount)} />
      </TableCell>
      <TableCell align="right">
        <RateDisplay base="CC" quote="USD" coinPrice={BigNumber(coin_price)} />
      </TableCell>
    </TableRow>
  );
};

interface TransactionAmountProps {
  amountCC: BigNumber;
}

const RecentAmountDisplay: React.FC<TransactionAmountProps> = ({ amountCC }) => {
  // This is forcing <AmountDisplay> to show a "+" sign for positive balance changes.
  // If the balance change is negative, the number already contains the minus sign.
  const sign = amountCC.isPositive() ? '+' : '';

  return (
    <Stack direction="column">
      <Typography className="tx-amount-cc">
        {sign}
        <AmountDisplay amount={amountCC} currency="CC" />
      </Typography>
    </Stack>
  );
};
