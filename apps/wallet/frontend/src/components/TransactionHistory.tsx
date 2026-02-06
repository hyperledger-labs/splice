// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import {
  AmountDisplay,
  ErrorDisplay,
  RateDisplay,
  Loading,
  ViewMoreButton,
  UpdateId,
  updateIdFromEventId,
} from '@lfdecentralizedtrust/splice-common-frontend';
import BigNumber from 'bignumber.js';
import formatISO from 'date-fns/formatISO';

import {
  AccountBalanceWallet,
  ArrowCircleLeftOutlined,
  ArrowCircleRightOutlined,
  ChangeCircleOutlined,
  ErrorOutlineOutlined,
  InfoOutlined,
} from '@mui/icons-material';
import {
  Icon,
  Stack,
  TableBody,
  Table,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
} from '@mui/material';
import Typography from '@mui/material/Typography';

import { Party } from '@daml/types';

import { usePrimaryParty, useTransactions } from '../hooks';
import useAmuletPrice from '../hooks/scan-proxy/useAmuletPrice';
import { BalanceChange, Transaction, Transfer } from '../models/models';
import { useWalletConfig } from '../utils/config';
import { shortenPartyId } from '../utils/partyId';
import BftAnsEntry from './BftAnsEntry';

const shortenContractId = (cid: string): string => {
  return `${cid.slice(0, 10)}…`;
};

const TransactionHistory: React.FC = () => {
  const txQuery = useTransactions();

  const amuletPriceQuery = useAmuletPrice();
  const primaryPartyId = usePrimaryParty();

  const isLoading = amuletPriceQuery.isLoading || txQuery.isLoading;
  const isError = amuletPriceQuery.isError || txQuery.isError || !primaryPartyId;

  const hasNoTransactions = (pagedTxs: Transaction[][]): boolean => {
    return (
      pagedTxs === undefined ||
      pagedTxs.length === 0 ||
      pagedTxs.every(p => p === undefined || p.length === 0)
    );
  };

  const pagedTransactions = txQuery.data ? txQuery.data.pages : [];

  return (
    <Stack mt={4} spacing={4} direction="column" justifyContent="center" id="tx-history">
      <Typography mt={6} variant="h4">
        Transaction History
      </Typography>
      {isLoading ? (
        <Loading />
      ) : isError ? (
        <ErrorDisplay message={'Error while fetching either transactions or amulet price.'} />
      ) : hasNoTransactions(pagedTransactions) ? (
        <Typography variant="h6">No Transactions yet</Typography>
      ) : (
        <TableContainer>
          <Table>
            <TableHead>
              <TableRow>
                <TableCell>Type</TableCell>
                <TableCell>Date</TableCell>
                <TableCell>Sender or Receiver</TableCell>
                <TableCell>Rewards Created</TableCell>
                <TableCell>Balance Change</TableCell>
                <TableCell>Update ID</TableCell>
              </TableRow>
            </TableHead>

            <TableBody>
              {pagedTransactions.map(
                transactions =>
                  transactions &&
                  transactions.map(tx => (
                    <TransactionHistoryRow
                      key={'tx-row-' + tx.id}
                      transaction={tx}
                      primaryPartyId={primaryPartyId}
                    />
                  ))
              )}
            </TableBody>
          </Table>
        </TableContainer>
      )}
      <ViewMoreButton
        label={
          txQuery.isFetchingNextPage
            ? 'Loading more...'
            : txQuery.hasNextPage
              ? 'Load More'
              : 'Nothing more to load'
        }
        loadMore={() => txQuery.fetchNextPage()}
        disabled={!txQuery.hasNextPage}
        idSuffix="transactions"
      />
    </Stack>
  );
};

interface TransactionHistoryRowProps {
  transaction: Transaction;
  primaryPartyId: Party;
}

const TransactionHistoryRow: React.FC<TransactionHistoryRowProps> = ({
  transaction,
  primaryPartyId,
}) => {
  const updateId =
    transaction.transactionType === 'notification' ? (
      <TableCell className="tx-row-cell-update-id" />
    ) : (
      <TableCell className="tx-row-cell-update-id">
        <UpdateId updateId={updateIdFromEventId(transaction.id)} />
      </TableCell>
    );

  return (
    <TableRow className={`tx-row tx-row-${transaction.transactionType}`}>
      <TableCell className="tx-row-cell-type">
        <TransactionIconAction transaction={transaction} primaryPartyId={primaryPartyId} />
      </TableCell>
      <TableCell className="tx-row-cell-date">
        <Typography>{formatISO(transaction.date)}</Typography>
      </TableCell>
      <TableCell className="tx-row-cell-receiver">
        <SenderReceiverInfo transaction={transaction} />
      </TableCell>
      <TableCell className="tx-row-cell-rewards">
        <RewardCollectedInfo transaction={transaction} />
      </TableCell>
      <TableCell className="tx-row-cell-balance-change">
        <TransactionAmount transaction={transaction} primaryPartyId={primaryPartyId} />
      </TableCell>
      {updateId}
    </TableRow>
  );
};

interface TransactionIconInfoProps {
  transaction: Transaction;
  primaryPartyId: Party;
}
const TransactionIconAction: React.FC<TransactionIconInfoProps> = ({
  transaction,
  primaryPartyId,
}) => {
  let icon = <></>;
  let text = '';
  switch (transaction.transactionType) {
    case 'balance_change':
      icon = <AccountBalanceWallet fontSize="small" />;
      text = 'Balance Change';
      break;
    case 'transfer': {
      const isUserTheSender = transaction.senderId === primaryPartyId;
      const noReceivers = transaction.receivers.length === 0;
      if (noReceivers) {
        icon = <ChangeCircleOutlined fontSize="small" />;
        text = 'Sent';
      } else if (isUserTheSender) {
        icon = <ArrowCircleRightOutlined fontSize="small" />;
        text = 'Sent';
      } else {
        icon = <ArrowCircleLeftOutlined fontSize="small" />;
        text = 'Received';
      }
      break;
    }
    case 'notification':
      icon = <InfoOutlined fontSize="small" />;
      text = 'Notification';
      break;
    case 'unknown':
      icon = <ErrorOutlineOutlined fontSize="small" />;
      text = 'Unknown Event';
      break;
  }

  return (
    <Stack direction="row" alignItems="center">
      <Icon sx={{ marginRight: '16px' }} fontSize="small">
        {icon}
      </Icon>
      <Stack>
        <Typography className="tx-action">{text}</Typography>
        <TransactionSubtypeText transaction={transaction} primaryPartyId={primaryPartyId} />
      </Stack>
    </Stack>
  );
};

const TransactionSubtypeText: React.FC<{ transaction: Transaction; primaryPartyId: string }> = ({
  transaction,
  primaryPartyId,
}) => {
  const subtype = transaction.transactionSubtype;
  const config = useWalletConfig();
  const { amuletName, nameServiceNameAcronym } = config.spliceInstanceNames;
  let text: string;
  // This should be replaced by localization in the future.
  switch (subtype.choice) {
    case 'WalletAppInstall_ExecuteBatch':
      // WalletAutomation
      switch (subtype.amulet_operation) {
        case 'CO_CompleteAcceptedTransfer':
          text = 'P2P Payment Failed';
          break;
        case 'CO_SubscriptionMakePayment':
          text = 'Subscription Payment Failed';
          break;
        case undefined:
        case null:
          text = 'Automation';
          break;
        default:
          console.warn(`Unknown Transaction ${amuletName} Operation`, subtype);
          text = subtype.choice;
      }
      break;
    case 'SubscriptionIdleState_MakePayment':
      // SubscriptionPaymentAccepted
      text = 'Subscription Payment Accepted';
      break;
    case 'SubscriptionPayment_Collect':
      // SubscriptionPaymentCollected
      text = 'Subscription Payment Collected';
      break;
    case 'AppPaymentRequest_Accept':
      // AppPaymentAccepted
      text = 'App Payment Accepted';
      break;
    case 'AmuletRules_BuyMemberTraffic':
      // ExtraTrafficPurchase
      text = 'Extra Traffic Purchase';
      break;
    // TODO(DACH-NY/canton-network-node#14568): Add frontend tests for the transfer pre-approval tx subtypes
    case 'AmuletRules_CreateTransferPreapproval':
      // TransferPreapprovalCreated
      text = 'Transfer Preapproval Created';
      break;
    case 'TransferPreapproval_Renew':
      // TransferPreapprovalRenewed
      text = 'Transfer Preapproval Renewed';
      break;
    case 'AcceptedAppPayment_Collect':
      // AppPaymentCollected
      text = 'App Payment Collected';
      break;
    case 'AcceptedTransferOffer_Complete':
      // P2PPaymentCompleted
      text = 'P2P Payment Completed';
      break;
    case 'SubscriptionRequest_AcceptAndMakePayment':
      // SubscriptionInitialPaymentAccepted
      text = 'Subscription Initial Payment Accepted';
      break;
    case 'AmuletRules_Transfer':
      // Transfer
      text = 'Transfer';
      break;
    case 'SubscriptionInitialPayment_Collect':
      // SubscriptionInitialPaymentCollected
      text = 'Subscription Initial Payment Collected';
      break;
    case 'AcceptedAppPayment_Expire':
      // AppPaymentExpired
      text = 'App Payment Expired';
      break;
    case 'AmuletRules_Mint':
      // Mint
      text = 'Mint';
      break;
    case 'Amulet_Expire':
      // AmuletExpired
      text = `${amuletName} Expired`;
      break;
    case 'SvRewardCoupon_ArchiveAsBeneficiary':
      // SvRewardCollected
      text = 'SV Reward Collected';
      break;
    case 'SubscriptionPayment_Reject':
      // SubscriptionPaymentRejected
      text = 'Subscription Payment Rejected';
      break;
    case 'LockedAmulet_Unlock':
      // LockedAmuletUnlocked
      text = `Locked ${amuletName} Unlocked`;
      break;
    case 'SubscriptionInitialPayment_Reject':
      // SubscriptionInitialPaymentRejected
      text = 'Subscription Initial Payment Rejected';
      break;
    case 'LockedAmulet_OwnerExpireLock':
      // LockedAmuletOwnerExpired
      text = `Locked ${amuletName} Owner Expired`;
      break;
    case 'LockedAmulet_ExpireAmulet':
      // LockedAmuletExpired
      text = `Locked ${amuletName} Expired`;
      break;
    case 'AcceptedAppPayment_Reject':
      // AppPaymentRejected
      text = 'App Payment Rejected';
      break;
    case 'SubscriptionInitialPayment_Expire':
      // SubscriptionInitialPaymentExpired
      text = 'Subscription Initial Payment Expired';
      break;
    case 'SubscriptionPayment_Expire':
      // SubscriptionPaymentExpired
      text = 'Subscription Payment Expired';
      break;
    case 'AmuletRules_DevNet_Tap':
      // Tap
      text = 'Tap';
      break;
    case 'SubscriptionIdleState_ExpireSubscription':
      // SubscriptionExpired
      text = 'Subscription Expired';
      break;
    case 'AnsRules_CollectInitialEntryPayment':
      // AnsEntryInitialPaymentCollected
      text = `${nameServiceNameAcronym.toUpperCase()} Entry Initial Payment Collected`;
      break;
    case 'AnsRules_CollectEntryRenewalPayment':
      // AnsEntryRenewalPaymentCollected
      text = `${nameServiceNameAcronym.toUpperCase()} Entry Renewal Payment Collected`;
      break;
    case 'TransferFactory_Transfer': {
      const transfer = toTransfer(transaction);
      // TODO(DACH-NY/canton-network-node#19607) Improve display
      const target =
        primaryPartyId == transfer.transferInstructionReceiver
          ? `from ${shortenPartyId(transfer.senderId)}`
          : `to ${shortenPartyId(transfer.transferInstructionReceiver!)}`;
      const description = transfer.description ? `: ${transfer.description}` : '';
      text = `Transfer offer ${shortenContractId(transfer.transferInstructionCid!)} for ${transfer.transferInstructionAmount} ${target}${description}`;
      break;
    }
    case 'TransferInstruction_Accept': {
      const transfer = toTransfer(transaction);
      text = `Transfer offer ${shortenContractId(transfer.transferInstructionCid!)} accepted`;
      break;
    }
    case 'TransferInstruction_Reject': {
      const transfer = toBalanceChange(transaction);
      text = `Transfer offer ${shortenContractId(transfer.transferInstructionCid!)} rejected`;
      break;
    }
    case 'TransferInstruction_Withdraw': {
      const transfer = toBalanceChange(transaction);
      text = `Transfer offer ${shortenContractId(transfer.transferInstructionCid!)} withdrawn`;
      break;
    }
    default:
      console.warn('Unknown Transaction Subtype', JSON.stringify(subtype));
      text = subtype.choice;
  }
  return (
    <Typography className="tx-subtype" variant="body2">
      ({text})
    </Typography>
  );
};

const toTransfer = (t: Transaction): Transfer => {
  if (t.transactionType == 'transfer') {
    return t;
  }
  throw new Error(`Expected transfer but got ${t.transactionType}`);
};

const toBalanceChange = (t: Transaction): BalanceChange => {
  if (t.transactionType == 'balance_change') {
    return t;
  }
  throw new Error(`Expected balance change but got ${t.transactionType}`);
};

const SenderReceiverInfo: React.FC<{ transaction: Transaction }> = ({ transaction }) => {
  const primaryPartyId = usePrimaryParty();

  if (transaction.transactionType !== 'transfer') {
    return <></>;
  }

  let senderOrReceiver;

  if (transaction.receivers.length === 0) {
    senderOrReceiver = (
      <Typography className="sender-or-receiver" data-selenium-text="Automation" variant="body1">
        Automation
      </Typography>
    );
  } else if (transaction.senderId !== primaryPartyId) {
    senderOrReceiver = (
      <BftAnsEntry className="sender-or-receiver" partyId={transaction.senderId} variant="body1" />
    );
  } else if (transaction.receivers.length === 1) {
    senderOrReceiver = (
      <BftAnsEntry
        className="sender-or-receiver"
        partyId={transaction.receivers[0].party}
        variant="body1"
      />
    );
  } else {
    senderOrReceiver = (
      <Typography
        className="sender-or-receiver"
        data-selenium-text="Multiple Recipients"
        variant="body1"
      >
        Multiple Recipients
      </Typography>
    );
  }

  return (
    <Stack direction="column" className="tx-party">
      {senderOrReceiver}
    </Stack>
  );
};

const RewardCollectedInfo: React.FC<{ transaction: Transaction }> = ({ transaction }) => {
  if (transaction.transactionType !== 'transfer') {
    return <></>;
  }

  const appRewards = BigNumber(transaction.appRewardsUsed || 0);
  const validatorRewards = BigNumber(transaction.validatorRewardsUsed || 0);
  const svRewards = BigNumber(transaction.svRewardsUsed || 0);

  const row = (type: string, label: string, amount: BigNumber) => [
    <Typography key={`tx-reward-${type}-label`}>{label}:</Typography>,
    <Typography key={`tx-reward-${type}-amulet`} className={`tx-reward-${type}-amulet`}>
      <AmountDisplay amount={amount} currency="AmuletUnit" />
    </Typography>,
  ];
  return (
    <Stack direction="column">
      {!appRewards.isZero() && row('app', 'App Rewards', appRewards)}
      {!validatorRewards.isZero() && row('validator', 'Validator Rewards', validatorRewards)}
      {!svRewards.isZero() && row('sv', 'SV Rewards', svRewards)}
    </Stack>
  );
};

interface TransactionAmountProps {
  transaction: Transaction;
  primaryPartyId: Party;
}

const TransactionAmount: React.FC<TransactionAmountProps> = ({ transaction, primaryPartyId }) => {
  if (transaction.transactionType === 'notification' || transaction.transactionType === 'unknown') {
    return <></>;
  }

  let amountAmulet: BigNumber;
  switch (transaction.transactionType) {
    case 'transfer':
      if (transaction.senderId === primaryPartyId) {
        amountAmulet = transaction.senderAmountCC;
      } else {
        amountAmulet = transaction.receivers.find(r => r.party === primaryPartyId)!.amount;
      }
      break;
    case 'balance_change':
      amountAmulet = transaction.receivers.find(r => r.party === primaryPartyId)!.amount;
      break;
  }

  // This is forcing <AmountDisplay> to show a "+" sign for positive balance changes.
  // If the balance change is negative, the number already contains the minus sign.
  const sign = amountAmulet.isPositive() ? '+' : '';

  const amuletPriceAtTimeOfTransaction = transaction.amuletPrice;

  return (
    <Stack direction="column">
      <Typography className="tx-amount-amulet">
        {sign}
        <AmountDisplay amount={amountAmulet} currency="AmuletUnit" />
      </Typography>
      <Stack direction="row" spacing={0.5}>
        <Typography variant="caption" className="tx-amount-usd">
          {sign}
          <AmountDisplay
            amount={amountAmulet}
            currency="AmuletUnit"
            convert="CCtoUSD"
            amuletPrice={amuletPriceAtTimeOfTransaction}
          />
        </Typography>
        {!amuletPriceAtTimeOfTransaction.isZero() && (
          <>
            <Typography variant="caption">@</Typography>
            <Typography variant="caption" className="tx-amount-rate">
              <RateDisplay
                base="AmuletUnit"
                quote="USDUnit"
                amuletPrice={amuletPriceAtTimeOfTransaction}
              />
            </Typography>
          </>
        )}
      </Stack>
    </Stack>
  );
};

export default TransactionHistory;
