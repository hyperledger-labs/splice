import * as React from 'react';
import BigNumber from 'bignumber.js';
import { AmountDisplay, DirectoryEntry, useInterval, useUserState } from 'common-frontend';
import formatISO from 'date-fns/formatISO';
import { useCallback, useState } from 'react';

import {
  AccountBalanceWallet,
  ArrowCircleLeftOutlined,
  ArrowCircleRightOutlined,
  PrecisionManufacturing,
} from '@mui/icons-material';
import {
  Button,
  Icon,
  Stack,
  styled,
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
                  coinPrice={coinPrice}
                  primaryPartyId={primaryPartyId}
                />
              );
            })}
          </TableBody>
        </Table>
      </TableContainer>
      <ViewMoreButton
        loadMore={() => {
          // TODO (#3573): this is currently only refreshing, because the order is not correct
          // const afterId: string | undefined =
          //   transactions.length > 0 ? transactions[transactions.length - 1].id : undefined;
          return fetchTransactionsAfterId();
        }}
      />
    </Stack>
  );
};

interface TransactionHistoryRowProps {
  transaction: Transaction;
  coinPrice: BigNumber;
  primaryPartyId: Party;
}

const TransactionHistoryRow: React.FC<TransactionHistoryRowProps> = ({
  transaction,
  coinPrice,
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
        <TransactionAmount
          transaction={transaction}
          coinPrice={coinPrice}
          primaryPartyId={primaryPartyId}
        />
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
    case 'automation':
      icon = <PrecisionManufacturing fontSize="small" />;
      text = 'Automation';
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
      <Typography className="tx-action">{text}</Typography>
    </Stack>
  );
};

const SenderReceiverInfo: React.FC<{ transaction: Transaction }> = ({ transaction }) => {
  const { primaryPartyId } = useUserState();

  if (
    transaction.transactionType === 'balance_change' ||
    transaction.transactionType === 'automation'
  ) {
    return <></>;
  }

  let senderOrReceiver;
  if (transaction.receivers.length === 0) {
    senderOrReceiver = <Typography variant="body1">Automation</Typography>;
  } else if (transaction.senderId !== primaryPartyId) {
    senderOrReceiver = (
      <Body1WithDirectoryEntry>
        <DirectoryEntry partyId={transaction.senderId} />
      </Body1WithDirectoryEntry>
    );
  } else if (transaction.receivers.length === 1) {
    senderOrReceiver = (
      <Body1WithDirectoryEntry>
        <DirectoryEntry partyId={transaction.receivers[0].party} />
      </Body1WithDirectoryEntry>
    );
  } else {
    senderOrReceiver = <Typography variant="body1">Multiple Recipients</Typography>;
  }

  return (
    <Stack direction="column" className="tx-party">
      {senderOrReceiver}
      <CaptionWithDirectoryEntry>
        via <DirectoryEntry partyId={transaction.providerId} />
      </CaptionWithDirectoryEntry>
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

// TODO (#3503): refactor into DirectoryEntry
const CaptionWithDirectoryEntry = styled('div')(({ theme }) => ({ ...theme.typography.caption }));
const Body1WithDirectoryEntry = styled('div')(({ theme }) => ({ ...theme.typography.caption }));

interface TransactionAmountProps {
  transaction: Transaction;
  coinPrice: BigNumber;
  primaryPartyId: Party;
}
const TransactionAmount: React.FC<TransactionAmountProps> = ({
  transaction,
  coinPrice,
  primaryPartyId,
}) => {
  let amountCC: BigNumber;
  let symbol: '+' | '-';
  switch (transaction.transactionType) {
    case 'automation':
      amountCC = transaction.senderAmountCC;
      symbol = '-';
      break;
    case 'transfer':
      if (transaction.senderId === primaryPartyId) {
        amountCC = transaction.senderAmountCC;
        symbol = '-';
      } else {
        amountCC = transaction.receivers.find(r => r.party === primaryPartyId)!.amount;
        symbol = '+';
      }
      break;
    case 'balance_change':
      amountCC = transaction.receivers.find(r => r.party === primaryPartyId)!.amount;
      symbol = '+';
      break;
  }

  // TODO (#3623): this should be the exchange rate at the time of the transaction,
  //              but it's not included in the response
  const totalUSDAmount = amountCC.times(coinPrice);

  return (
    <Stack direction="column">
      <Typography className="tx-amount-cc">
        {symbol}
        <AmountDisplay amount={amountCC.toString()} />
      </Typography>
      <Stack direction="row" spacing={0.5}>
        <Typography variant="caption" className="tx-amount-usd">
          {symbol}
          <AmountDisplay amount={totalUSDAmount.toString()} currency="USD" />
        </Typography>
        <Typography variant="caption">@</Typography>
        <Typography variant="caption" className="tx-amount-rate">
          <AmountDisplay amount={BigNumber(1).div(coinPrice).toString()} currency="CC/USD" />
        </Typography>
      </Stack>
    </Stack>
  );
};

export default TransactionHistory;
