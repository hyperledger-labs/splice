// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import BigNumber from 'bignumber.js';
import {
  AmountDisplay,
  AnsEntry,
  ErrorDisplay,
  Loading,
  RateDisplay,
  TitledTable,
} from 'common-frontend';
import { useActivity } from 'common-frontend/scan-api';
import { useInView } from 'react-intersection-observer';
import { ListActivityResponseItem, SenderAmount, Transfer, AmuletAmount } from 'scan-openapi';

import {
  Box,
  CircularProgress,
  Stack,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
} from '@mui/material';
import Typography from '@mui/material/Typography';

export const ActivityTable: React.FC = () => {
  const {
    data: activityData,
    isLoading,
    isError,
    fetchNextPage,
    hasNextPage,
    isFetchingNextPage,
  } = useActivity();

  const { ref } = useInView({
    onChange: inView => {
      if (inView && hasNextPage && !isFetchingNextPage) {
        fetchNextPage();
      }
    },
  });

  const hasNoActivities = (pagedActivities: ListActivityResponseItem[][]): boolean => {
    return (
      pagedActivities === undefined ||
      pagedActivities.length === 0 ||
      pagedActivities.every(p => p === undefined || p.length === 0)
    );
  };

  const pagedActivities = activityData ? activityData.pages : [];

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
              <TableCell align="right">Rewards Created</TableCell>
              <TableCell align="right">Total Fees Burnt</TableCell>
              <TableCell align="right">Price</TableCell>
            </TableRow>
          </TableHead>

          <TableBody>
            {pagedActivities.map(
              activities =>
                activities &&
                activities
                  .flatMap(item => toActivities(item))
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

      <Box ref={ref} sx={{ alignSelf: 'center' }}>
        {isFetchingNextPage ? (
          <CircularProgress />
        ) : hasNextPage ? (
          <Typography>More activities available</Typography>
        ) : (
          <Typography>No more activities</Typography>
        )}
      </Box>
    </Stack>
  );
};

export default ActivityTable;

type RewardsCollected = { app?: BigNumber; validator?: BigNumber; sv?: BigNumber };

interface ActivityView {
  activityType: string;
  provider: string;
  sender: string;
  receiver: string | 'Multiple' | 'None';
  feesBurnt: BigNumber;
  transferAmount: BigNumber;
  rewardsCollected: RewardsCollected;
  amuletPrice: BigNumber;
  eventId: string;
}

function toActivities(item: ListActivityResponseItem): ActivityView[] {
  function getActivity(item: ListActivityResponseItem): ActivityView {
    switch (item.activity_type) {
      case 'transfer':
        return getActivityFromTransfer(item.transfer!);
      case 'devnet_tap':
        return getActivityFromTap(item.tap!);
      case 'mint':
        return getActivityFromMint(item.mint!);
    }
  }

  function feesFromSender(senderAmount: SenderAmount): BigNumber {
    return BigNumber(senderAmount.holding_fees)
      .plus(BigNumber(senderAmount.sender_fee))
      .plus(BigNumber(senderAmount.sender_change_fee));
  }

  function getActivityFromTransfer(transfer: Transfer): ActivityView {
    let activityType;
    let receiver;
    let feesBurnt;
    let rewardsCollected: RewardsCollected = {};

    const receivers = transfer.receivers;

    if (receivers.length === 0) {
      // We assume that all transfers without receivers are caused by the wallet automation
      // This might not be accurate, but it's less confusing than "Self-transfer"
      activityType = 'Automation';
    } else {
      activityType = 'Transfer';
    }

    const appRewards = BigNumber(transfer.sender.input_app_reward_amount || 0);
    if (!appRewards.isZero()) {
      rewardsCollected.app = appRewards;
    }
    const validatorRewards = BigNumber(transfer.sender.input_validator_reward_amount || 0);
    if (!validatorRewards.isZero()) {
      rewardsCollected.validator = validatorRewards;
    }
    const svRewards = BigNumber(transfer.sender.input_sv_reward_amount || 0);
    if (!svRewards.isZero()) {
      rewardsCollected.sv = svRewards;
    }

    const provider = transfer.provider;

    const nrReceivers = receivers.length;
    if (nrReceivers === 0) {
      receiver = 'None';
    } else if (nrReceivers === 1) {
      receiver = receivers[0].party;
    } else {
      receiver = 'Multiple';
    }
    const senderAmount = transfer.sender;
    const receiverFees = receivers
      .map(r => BigNumber(r.receiver_fee))
      .reduce((prev, cur) => prev.plus(cur), BigNumber(0));
    feesBurnt = feesFromSender(senderAmount).plus(receiverFees);

    const transferAmount = receivers
      .map(r => BigNumber(r.amount))
      .reduce((prev, cur) => prev.plus(cur), BigNumber(0));

    return {
      activityType: activityType,
      provider: provider,
      sender: transfer.sender.party,
      receiver: receiver,
      feesBurnt: feesBurnt,
      transferAmount: transferAmount,
      rewardsCollected: rewardsCollected,
      amuletPrice: BigNumber(item.amulet_price),
      eventId: item.event_id,
    };
  }

  function getActivityFromMint(mint: AmuletAmount): ActivityView {
    return {
      activityType: 'Mint',
      provider: mint.amulet_owner,
      sender: mint.amulet_owner,
      receiver: mint.amulet_owner,
      feesBurnt: BigNumber(0),
      transferAmount: BigNumber(mint.amulet_amount),
      rewardsCollected: {},
      amuletPrice: BigNumber(item.amulet_price),
      eventId: item.event_id,
    };
  }

  function getActivityFromTap(tap: AmuletAmount): ActivityView {
    return {
      activityType: 'Tap',
      provider: tap.amulet_owner,
      sender: tap.amulet_owner,
      receiver: tap.amulet_owner,
      feesBurnt: BigNumber(0),
      transferAmount: BigNumber(tap.amulet_amount),
      rewardsCollected: {},
      amuletPrice: BigNumber(item.amulet_price),
      eventId: item.event_id,
    };
  }

  const activity = getActivity(item);

  return [activity];
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
        <AnsEntry partyId={activity.provider} />
      </TableCell>
      <TableCell>
        <AnsEntry partyId={activity.sender} />
      </TableCell>
      <TableCell>
        {(() => {
          switch (activity.receiver) {
            case 'None':
              return null;
            case 'Multiple':
              return (
                <Typography className="receiver" data-selenium-text="Multiple" variant="body1">
                  Multiple
                </Typography>
              );
            default:
              return <AnsEntry className="receiver" partyId={activity.receiver} />;
          }
        })()}
      </TableCell>
      <TableCell align="right">
        <ActivityAmountDisplay
          amountAmulet={activity.transferAmount}
          amuletPrice={activity.amuletPrice}
        />
      </TableCell>
      <TableCell align="right">
        <ActivityRewardDisplay rewards={activity.rewardsCollected} />
      </TableCell>
      <TableCell align="right">
        <ActivityAmountDisplay
          amountAmulet={activity.feesBurnt}
          amuletPrice={activity.amuletPrice}
        />
      </TableCell>
      <TableCell align="right">
        <RateDisplay base="AmuletUnit" quote="USDUnit" amuletPrice={activity.amuletPrice} />
      </TableCell>
    </TableRow>
  );
};

interface TransactionAmountProps {
  amountAmulet: BigNumber;
  amuletPrice: BigNumber;
}

const ActivityAmountDisplay: React.FC<TransactionAmountProps> = ({ amountAmulet, amuletPrice }) => {
  if (amountAmulet.isZero()) {
    return null;
  } else {
    return (
      <Stack direction="column">
        <Typography className="tx-amount-amulet">
          <AmountDisplay amount={amountAmulet} currency="AmuletUnit" />
        </Typography>
        <Typography variant="caption" className="tx-amount-usd">
          <AmountDisplay
            amount={amountAmulet}
            currency="AmuletUnit"
            convert="CCtoUSD"
            amuletPrice={amuletPrice}
          />
        </Typography>
      </Stack>
    );
  }
};

interface TransactionRewardProps {
  rewards: RewardsCollected;
}

const ActivityRewardDisplay: React.FC<TransactionRewardProps> = ({ rewards }) => {
  const row = (type: string, label: string, amount: BigNumber) => [
    <Typography key={`tx-reward-${type}-label`}>{label}:</Typography>,
    <Typography key={`tx-reward-${type}-amulet`} className={`tx-reward-${type}-amulet`}>
      <AmountDisplay amount={amount} currency="AmuletUnit" />
    </Typography>,
  ];
  return (
    <Stack direction="column">
      {rewards.app && row('app', 'App Rewards', rewards.app)}
      {rewards.validator && row('validator', 'Validator Rewards', rewards.validator)}
      {rewards.sv && row('sv', 'SV Rewards', rewards.sv)}
    </Stack>
  );
};
