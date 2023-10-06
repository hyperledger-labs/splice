import * as React from 'react';
import BigNumber from 'bignumber.js';
import {
  AmountDisplay,
  DirectoryEntry,
  ErrorDisplay,
  Loading,
  RateDisplay,
  TitledTable,
} from 'common-frontend';
import { useGetSvcPartyId, useActivity } from 'common-frontend/scan-api';
import { ListActivityResponseItem, SenderAmount, Transfer, CoinAmount } from 'scan-openapi';

import { Button, Stack, TableBody, TableCell, TableHead, TableRow } from '@mui/material';
import Typography from '@mui/material/Typography';

export const ActivityTable: React.FC = () => {
  const activityQuery = useActivity();
  const svcPartyIdQuery = useGetSvcPartyId();
  const hasNoActivities = (pagedActivities: ListActivityResponseItem[][]): boolean => {
    return (
      pagedActivities === undefined ||
      pagedActivities.length === 0 ||
      pagedActivities.every(p => p === undefined || p.length === 0)
    );
  };
  const isLoading = activityQuery.isLoading || svcPartyIdQuery.isLoading;
  const isError = activityQuery.isError || svcPartyIdQuery.isError;

  const pagedActivities = activityQuery.data ? activityQuery.data.pages : [];
  return (
    <Stack spacing={4} direction="column" data-testid="activity-table">
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
              <TableCell align="right">Transfer Amount</TableCell>
              <TableCell align="right">Total Fees Burnt</TableCell>
              <TableCell align="right">Price</TableCell>
            </TableRow>
          </TableHead>

          <TableBody>
            {pagedActivities.map(
              activities =>
                activities &&
                activities
                  .flatMap(item => toActivities(item, svcPartyIdQuery.data))
                  .map(activity => (
                    <ActivityRow
                      key={activity.eventId + activity.activityType}
                      activity={activity}
                    />
                  ))
            )}
          </TableBody>
        </TitledTable>
      )}
      <ViewMoreButton
        label={
          activityQuery.isFetchingNextPage
            ? 'Loading more...'
            : activityQuery.hasNextPage
            ? 'Load More'
            : 'Nothing more to load'
        }
        loadMore={() => activityQuery.fetchNextPage()}
        disabled={!activityQuery.hasNextPage}
      />
    </Stack>
  );
};

interface ViewMoreButtonProps {
  loadMore: () => void;
  label: string;
  disabled: boolean;
}
// TODO(#7764) reuse between paged tables, wallet transaction history and here.
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

export default ActivityTable;

interface ActivityView {
  activityType: string;
  provider: string;
  sender: string;
  receiver: string | 'Multiple';
  feesBurnt: BigNumber;
  transferAmount: BigNumber;
  coinPrice: BigNumber;
  eventId: string;
}

function toActivities(item: ListActivityResponseItem, svcPartyId: string): ActivityView[] {
  function getActivity(item: ListActivityResponseItem): ActivityView {
    switch (item.activity_type) {
      case 'transfer':
        return getActivityFromTransfer(item.transfer!);
      case 'devnet_tap':
        return getActivityFromTap(item.tap!);
      case 'mint':
        return getActivityFromMint(item.mint!);
      case 'sv_reward_collected':
        return getActivityFromSvRewardCollected(item.sv_reward_collected!);
    }
  }

  function feesFromSender(senderAmount: SenderAmount): BigNumber {
    return BigNumber(senderAmount.holding_fees)
      .plus(BigNumber(senderAmount.sender_fee))
      .plus(BigNumber(senderAmount.sender_change_fee));
  }

  function getActivityFromTransfer(transfer: Transfer): ActivityView {
    const receivers = transfer.receivers;
    let activityType;
    let provider;
    let receiver;
    let feesBurnt;
    let transferAmount;
    if (transfer.receivers.length === 0) {
      activityType = 'Merge Fee Burn';
    } else {
      activityType = 'Transfer';
    }
    if (transfer.receivers.length === 0) {
      provider = svcPartyId;
    } else {
      provider = transfer.provider;
    }
    const nrReceivers = receivers.length;
    if (nrReceivers === 0) {
      receiver = svcPartyId;
    } else if (nrReceivers === 1) {
      receiver = receivers[0].party;
    } else {
      receiver = 'Multiple';
    }
    const senderAmount = transfer.sender;
    if (receivers.length === 0) {
      feesBurnt = feesFromSender(senderAmount);
    } else if (receivers.length === 1) {
      const r = receivers[0];
      feesBurnt = feesFromSender(senderAmount).plus(BigNumber(r.receiver_fee));
    } else {
      const receiverFees = transfer.receivers
        .map(r => BigNumber(r.receiver_fee))
        .reduce((prev, cur) => prev.plus(cur));
      feesBurnt = feesFromSender(senderAmount).plus(receiverFees);
    }
    if (receivers.length === 0) {
      transferAmount = BigNumber(0);
    } else if (receivers.length === 1) {
      const r = receivers[0];
      transferAmount = BigNumber(r.amount);
    } else {
      transferAmount = receivers
        .map(r => BigNumber(r.amount))
        .reduce((prev, cur) => prev.plus(cur));
    }
    return {
      activityType: activityType,
      provider: provider,
      sender: transfer.sender.party,
      receiver: receiver,
      feesBurnt: feesBurnt,
      transferAmount: transferAmount,
      coinPrice: BigNumber(item.coin_price),
      eventId: item.event_id,
    };
  }

  function getActivityFromMint(mint: CoinAmount): ActivityView {
    return {
      activityType: 'Mint',
      provider: mint.coin_owner,
      sender: mint.coin_owner,
      receiver: mint.coin_owner,
      feesBurnt: BigNumber(0),
      transferAmount: BigNumber(mint.coin_amount),
      coinPrice: BigNumber(item.coin_price),
      eventId: item.event_id,
    };
  }

  function getActivityFromTap(tap: CoinAmount): ActivityView {
    return {
      activityType: 'Tap',
      provider: tap.coin_owner,
      sender: tap.coin_owner,
      receiver: tap.coin_owner,
      feesBurnt: BigNumber(0),
      transferAmount: BigNumber(tap.coin_amount),
      coinPrice: BigNumber(item.coin_price),
      eventId: item.event_id,
    };
  }

  function getActivityFromSvRewardCollected(svr: CoinAmount): ActivityView {
    return {
      activityType: 'SV Reward Collected',
      provider: svcPartyId,
      sender: svcPartyId,
      receiver: svr.coin_owner,
      feesBurnt: BigNumber(0),
      transferAmount: BigNumber(svr.coin_amount),
      coinPrice: BigNumber(item.coin_price),
      eventId: item.event_id,
    };
  }

  const activity = getActivity(item);

  const activities = [activity];

  if (item.transfer != null) {
    const transfer = item.transfer!;
    const appRewardAmount = BigNumber(transfer.sender.input_app_reward_amount ?? '0');
    if (!appRewardAmount.isEqualTo(BigNumber(0))) {
      let receiver;
      if (transfer.receivers.length === 0) {
        receiver = activity.sender;
      } else {
        receiver = activity.receiver;
      }
      activities.push({
        ...activity,
        activityType: 'App Reward Collected',
        provider: svcPartyId,
        sender: svcPartyId,
        receiver: receiver,
        transferAmount: appRewardAmount,
        feesBurnt: BigNumber(0),
      });
    }
    const validatorRewardAmount = BigNumber(transfer.sender.input_validator_reward_amount ?? '0');
    if (!validatorRewardAmount.isEqualTo(BigNumber(0))) {
      let receiver;
      if (transfer.receivers.length === 0) {
        receiver = activity.sender;
      } else {
        receiver = activity.receiver;
      }
      activities.push({
        ...activity,
        activityType: 'Validator Reward Collected',
        provider: svcPartyId,
        sender: svcPartyId,
        receiver: receiver,
        transferAmount: validatorRewardAmount,
        feesBurnt: BigNumber(0),
      });
    }
  }
  return activities;
}

interface ActivityRowProps {
  activity: ActivityView;
}

const ActivityRow: React.FC<ActivityRowProps> = ({ activity }) => {
  return (
    <TableRow>
      <TableCell>
        <Typography className="activity_type" variant="body1">
          {activity.activityType}
        </Typography>
      </TableCell>
      <TableCell>
        <DirectoryEntry partyId={activity.provider} />
      </TableCell>
      <TableCell>
        <DirectoryEntry partyId={activity.sender} />
      </TableCell>
      <TableCell>
        {(() => {
          switch (activity.receiver) {
            case 'Multiple':
              return (
                <Typography className="receiver" data-selenium-text="Multiple" variant="body1">
                  Multiple
                </Typography>
              );
            default:
              return <DirectoryEntry className="receiver" partyId={activity.receiver} />;
          }
        })()}
      </TableCell>
      <TableCell align="right">
        <ActivityAmountDisplay amountCC={activity.transferAmount} />
      </TableCell>
      <TableCell align="right">
        <ActivityAmountDisplay amountCC={activity.feesBurnt} />
      </TableCell>
      <TableCell align="right">
        <RateDisplay base="CC" quote="USD" coinPrice={activity.coinPrice} />
      </TableCell>
    </TableRow>
  );
};

interface TransactionAmountProps {
  amountCC: BigNumber;
}

const ActivityAmountDisplay: React.FC<TransactionAmountProps> = ({ amountCC }) => {
  return (
    <Stack direction="column">
      <Typography className="tx-amount-cc">
        <AmountDisplay amount={amountCC} currency="CC" />
      </Typography>
    </Stack>
  );
};
