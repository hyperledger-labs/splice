import * as React from 'react';
import BigNumber from 'bignumber.js';
import {
  AmountDisplay,
  DirectoryEntry,
  ErrorDisplay,
  RateDisplay,
  useUserState,
} from 'common-frontend';
import Loading from 'common-frontend/lib/components/Loading';
import formatISO from 'date-fns/formatISO';

import {
  AccountBalanceWallet,
  ArrowCircleLeftOutlined,
  ArrowCircleRightOutlined,
} from '@mui/icons-material';
import {
  Button,
  Icon,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableRow,
} from '@mui/material';
import Typography from '@mui/material/Typography';

import { Party } from '@daml/types';

import { useCoinPrice, useTransactions } from '../hooks';
import { Transaction } from '../models/models';

const TransactionHistory: React.FC = () => {
  const txQuery = useTransactions();

  const coinPriceQuery = useCoinPrice();
  const { primaryPartyId } = useUserState();

  const isLoading = coinPriceQuery.isLoading || txQuery.isLoading;
  const isError = coinPriceQuery.isError || txQuery.isError || !primaryPartyId;

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
        <ErrorDisplay
          message={'Error while fetching either transactions or coin price.'}
          // renderAs="tr"
        />
      ) : hasNoTransactions(pagedTransactions) ? (
        <Typography variant="h6">No Transactions yet</Typography>
      ) : (
        <TableContainer>
          <Table>
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
  return (
    <TableRow className="tx-row">
      <TableCell>
        <TransactionIconAction transaction={transaction} primaryPartyId={primaryPartyId} />
      </TableCell>
      <TableCell>
        <Typography>{formatISO(transaction.date)}</Typography>
      </TableCell>
      <TableCell>
        <SenderReceiverInfo transaction={transaction} />
      </TableCell>
      <TableCell>
        <TransactionAmount transaction={transaction} primaryPartyId={primaryPartyId} />
      </TableCell>
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
    case 'transfer':
      const isUserTheSender = transaction.senderId === primaryPartyId;
      if (isUserTheSender) {
        icon = <ArrowCircleRightOutlined fontSize="small" />;
        text = 'Sent';
      } else {
        icon = <ArrowCircleLeftOutlined fontSize="small" />;
        text = 'Received';
      }
      break;
  }

  return (
    <Stack direction="row" alignItems="center">
      <Icon sx={{ marginRight: '16px' }} fontSize="small">
        {icon}
      </Icon>
      <Stack>
        <Typography className="tx-action">{text}</Typography>
        <Typography className="tx-subtype" variant="body2">
          ({transaction.transactionSubtype})
        </Typography>
      </Stack>
    </Stack>
  );
};

const SenderReceiverInfo: React.FC<{ transaction: Transaction }> = ({ transaction }) => {
  const { primaryPartyId } = useUserState();

  if (transaction.transactionType === 'balance_change') {
    return <></>;
  }

  let senderOrReceiver;
  if (transaction.receivers.length === 0) {
    senderOrReceiver = <Typography variant="body1">Automation</Typography>;
  } else if (transaction.senderId !== primaryPartyId) {
    senderOrReceiver = <DirectoryEntry partyId={transaction.senderId} variant="body1" />;
  } else if (transaction.receivers.length === 1) {
    senderOrReceiver = <DirectoryEntry partyId={transaction.receivers[0].party} variant="body1" />;
  } else {
    senderOrReceiver = <Typography variant="body1">Multiple Recipients</Typography>;
  }

  return (
    <Stack direction="column" className="tx-party">
      {senderOrReceiver}
      <Stack direction="row" alignItems="center" spacing={1}>
        <Typography variant="caption">via </Typography>
        <DirectoryEntry partyId={transaction.providerId} variant="caption" />
      </Stack>
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

interface TransactionAmountProps {
  transaction: Transaction;
  primaryPartyId: Party;
}
const TransactionAmount: React.FC<TransactionAmountProps> = ({ transaction, primaryPartyId }) => {
  let amountCC: BigNumber;
  switch (transaction.transactionType) {
    case 'transfer':
      if (transaction.senderId === primaryPartyId) {
        amountCC = transaction.senderAmountCC;
      } else {
        amountCC = transaction.receivers.find(r => r.party === primaryPartyId)!.amount;
      }
      break;
    case 'balance_change':
      amountCC = transaction.receivers.find(r => r.party === primaryPartyId)!.amount;
      break;
  }

  // This is forcing <AmountDisplay> to show a "+" sign for positive balance changes.
  // If the balance change is negative, the number already contains the minus sign.
  const sign = amountCC.isPositive() ? '+' : '';

  const coinPriceAtTimeOfTransaction = transaction.coinPrice;

  return (
    <Stack direction="column">
      <Typography className="tx-amount-cc">
        {sign}
        <AmountDisplay amount={amountCC} currency="CC" />
      </Typography>
      <Stack direction="row" spacing={0.5}>
        <Typography variant="caption" className="tx-amount-usd">
          {sign}
          <AmountDisplay
            amount={amountCC}
            currency="CC"
            convert="CCtoUSD"
            coinPrice={coinPriceAtTimeOfTransaction}
          />
        </Typography>
        <Typography variant="caption">@</Typography>
        <Typography variant="caption" className="tx-amount-rate">
          <RateDisplay base="CC" quote="USD" coinPrice={coinPriceAtTimeOfTransaction} />
        </Typography>
      </Stack>
    </Stack>
  );
};

export default TransactionHistory;
