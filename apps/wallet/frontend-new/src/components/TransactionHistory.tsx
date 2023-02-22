import * as React from 'react';

import { ArrowCircleLeftOutlined, ArrowCircleRightOutlined } from '@mui/icons-material';
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

import { Transaction } from '../models/models';
import AmountDisplay from './AmountDisplay';

interface TransactionHistoryProps {
  transactions: Transaction[];
}

const TransactionHistory: React.FC<TransactionHistoryProps> = props => (
  <Stack mt={4} spacing={4} direction="column" justifyContent="center">
    <Typography mt={6} variant="h4">
      Transaction History
    </Typography>
    <TableContainer>
      <Table>
        <TableBody>
          {props.transactions.map((tx, index) => {
            return <TransactionHistoryRow key={'tx-row-' + index} transaction={tx} />;
          })}
        </TableBody>
      </Table>
    </TableContainer>
    <Button variant="outlined" size="small" color="secondary">
      View More
    </Button>
  </Stack>
);

interface TransactionHistoryRowProps {
  transaction: Transaction;
}

const TransactionHistoryRow = (props: TransactionHistoryRowProps) => {
  const tx = props.transaction;
  return (
    <TableRow>
      <TableCell>
        <Stack direction="row" alignItems="center">
          <Icon sx={{ marginRight: '16px' }} fontSize="small">
            {tx.action === 'Sent' ? (
              <ArrowCircleLeftOutlined fontSize="small" />
            ) : (
              <ArrowCircleRightOutlined fontSize="small" />
            )}
          </Icon>
          <Typography>{tx.action}</Typography>
        </Stack>
      </TableCell>
      <TableCell>
        <Typography>{tx.date}</Typography>
      </TableCell>
      <TableCell>
        <Stack direction="column">
          <Typography>{tx.recipientId}</Typography>
          <Typography variant="caption">via {tx.providerId}</Typography>
        </Stack>
      </TableCell>
      <TableCell>
        <Stack direction="column">
          <Typography>
            <AmountDisplay amount={tx.totalCCAmount} />
          </Typography>
          <Typography variant="caption">
            <AmountDisplay amount={tx.totalUSDAmount} currency="USD" /> @{' '}
            <AmountDisplay amount={tx.conversionRate} currency="CC/USD" />
          </Typography>
        </Stack>
      </TableCell>
    </TableRow>
  );
};

export default TransactionHistory;
