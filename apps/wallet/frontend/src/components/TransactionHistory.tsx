import * as React from 'react';
import BigNumber from 'bignumber.js';
import {
  AmountDisplay,
  DirectoryEntry,
  RateDisplay,
  useInterval,
  useUserState,
} from 'common-frontend';
import formatISO from 'date-fns/formatISO';
import { useCallback, useState } from 'react';

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

import { useCoinPrice } from '../contexts/CoinPriceContext';
import { useWalletClient } from '../contexts/WalletServiceContext';
import { Transaction } from '../models/models';
import Loading from './Loading';

const TransactionHistory: React.FC = () => {
  const [transactions, setTransactions] = useState<Transaction[]>([]);
  const { listTransactions } = useWalletClient();

  const fetchTransactionsAfterId = useCallback(
    async (beginAfterId?: string) => {
      const transactions = await listTransactions(beginAfterId);
      setTransactions(transactions);
    },
    [listTransactions]
  );

  const fetchTransactions = useCallback(
    () => fetchTransactionsAfterId(),
    [fetchTransactionsAfterId]
  );

  useInterval(fetchTransactions);

  const coinPrice = useCoinPrice();
  const { primaryPartyId } = useUserState();

  if (!coinPrice || !primaryPartyId) {
    return <Loading />;
  }

  return (
    <Stack mt={4} spacing={4} direction="column" justifyContent="center">
      <Typography mt={6} variant="h4">
        Transaction History
      </Typography>
      <TableContainer>
        <Table>
          <TableBody>
            {transactions.map(tx => {
              return (
                <TransactionHistoryRow
                  key={'tx-row-' + tx.id}
                  transaction={tx}
                  primaryPartyId={primaryPartyId}
                />
              );
            })}
          </TableBody>
        </Table>
      </TableContainer>
      <ViewMoreButton
        loadMore={() => {
          // TODO(#3792): this is loading more data, but the new data is not added to the current results
          const afterId: string | undefined =
            transactions.length > 0 ? transactions[transactions.length - 1].id : undefined;
          return fetchTransactionsAfterId(afterId);
        }}
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
  loadMore: () => Promise<void>;
}
const ViewMoreButton: React.FC<ViewMoreButtonProps> = ({ loadMore }) => {
  return (
    <Button variant="outlined" size="small" color="secondary" onClick={loadMore}>
      View More
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
