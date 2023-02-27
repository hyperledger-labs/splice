import * as React from 'react';
import { AmountDisplay } from 'common-frontend';

import {
  Box,
  Button,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';

import { Subscription } from '../models/models';

const Subscriptions: React.FC = () => {
  const subscriptions: Subscription[] = [
    {
      provider: { description: 'Service Desc.', cns: 'alice.cns' },
      price: { amount: '31', currency: 'CC', perPeriod: '30 days' },
      nextPaymentDue: '22-02-2023',
    },
    {
      provider: { description: 'Service Desc.', cns: 'bob.cns' },
      price: { amount: '130.12', currency: 'USD', perPeriod: '30 days' },
      nextPaymentDue: '23-02-2023',
    },
    {
      provider: { description: 'Service Desc.', cns: 'thomas.cns' },
      price: { amount: '31', currency: 'CC', perPeriod: '30 days' },
      nextPaymentDue: '24-02-2023',
    },
    {
      provider: { description: 'Service Desc.', cns: 'kim.cns' },
      price: { amount: '130.12', currency: 'USD', perPeriod: '30 days' },
      nextPaymentDue: '25-02-2023',
    },
  ];

  return (
    <Stack marginY={10} spacing={2}>
      <Typography variant="h6" fontWeight="bold">
        Your Subscriptions
      </Typography>
      <TableContainer>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>Provider</TableCell>
              <TableCell align="right">Price</TableCell>
              <TableCell>Payment Due</TableCell>
              <TableCell>Actions</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {subscriptions.map((subscription, index) => {
              return <SubscriptionRow key={'subscription-' + index} subscription={subscription} />;
            })}
          </TableBody>
        </Table>
      </TableContainer>
    </Stack>
  );
};

const SubscriptionRow: React.FC<{ subscription: Subscription }> = ({ subscription }) => {
  const { nextPaymentDue, price, provider } = subscription;
  return (
    <TableRow>
      <TableCell>
        <Provider {...provider} />
      </TableCell>
      <TableCell align="right">
        <Price {...price} />
      </TableCell>
      <TableCell>
        <PaymentDue due={nextPaymentDue} daysLeft={2} />
      </TableCell>
      <TableCell>
        <Button variant="pill" size="small">
          Cancel
        </Button>
      </TableCell>
    </TableRow>
  );
};

const Provider: React.FC<{ description: string; cns: string }> = ({ description, cns }) => {
  return (
    <Stack>
      <Typography variant="h6">{description}</Typography>
      <Typography variant="caption">{cns}</Typography>
    </Stack>
  );
};

const Price: React.FC<{ amount: string; currency: string; perPeriod: string }> = ({
  amount,
  currency,
  perPeriod,
}) => {
  const exchangeRateUSDToCC = 10; // 1 USD = 10 CC
  let converted;
  if (currency === 'CC') {
    converted = {
      amount: Number(amount) / exchangeRateUSDToCC,
      currency: 'USD',
      exchangeRateToShow: 1 / exchangeRateUSDToCC,
    };
  } else {
    converted = {
      amount: Number(amount) * exchangeRateUSDToCC,
      currency: 'CC',
      exchangeRateToShow: exchangeRateUSDToCC,
    };
  }
  return (
    <Stack>
      <Box
        display="flex"
        flexDirection="row"
        justifyContent="flex-end"
        alignItems="baseline"
        gap={1}
      >
        <Typography variant="h6">
          <AmountDisplay amount={amount} currency={currency} />
        </Typography>
        per {perPeriod}
      </Box>
      <Typography variant="caption">
        <AmountDisplay amount={converted.amount.toString()} currency={converted.currency} /> @{' '}
        {converted.exchangeRateToShow}
        {currency}/{converted.currency}
      </Typography>
    </Stack>
  );
};

const PaymentDue: React.FC<{ due: string; daysLeft: number }> = ({ due, daysLeft }) => {
  return (
    <Stack>
      <Typography variant="h6">{daysLeft} days</Typography>
      <Typography variant="caption">{due}</Typography>
    </Stack>
  );
};
export default Subscriptions;
