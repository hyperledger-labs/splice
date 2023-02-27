import { AmountDisplay } from 'common-frontend';
import React from 'react';

import { Box, Button, Container, Stack, Typography } from '@mui/material';

import PaymentHeader from '../components/PaymentHeader';
import { Subscription } from '../models/models';

export const ConfirmSubscription: React.FC = () => {
  const via = 'credap.cns';
  return (
    <Stack minHeight="100vh">
      <PaymentHeader />
      <Box bgcolor="colors.neutral.25" flex={1}>
        <Container maxWidth="md">
          <Stack alignItems="center" paddingTop={4} spacing={4}>
            <Stack alignItems="center" spacing={1}>
              <Typography variant="h5">Confirm Subscription</Typography>
              <Typography variant="body2">via {via}</Typography>
            </Stack>
            <SubscriptionContainer />
          </Stack>
        </Container>
      </Box>
    </Stack>
  );
};

export default ConfirmSubscription;

const SubscriptionContainer: React.FC = () => {
  const subscription: Subscription = {
    provider: { description: 'Canton Name Service', cns: 'alice.cns' },
    price: { amount: '8.0', currency: 'CC', perPeriod: '30 days' },
    nextPaymentDue: '22-02-2023',
  };
  const exchangeRateUSDToCC = 10; // 1 USD = 10 CC
  const usd = 0.8;
  return (
    <Container maxWidth="xl">
      <Box bgcolor="colors.neutral.20" border={1} borderColor="colors.neutral.30">
        <Stack alignItems="center" spacing={4} marginY={4}>
          <Stack alignItems="center">
            <Typography variant="body1">Description</Typography>
            <Typography variant="h6">"{subscription.provider.description}"</Typography>
          </Stack>
          <Typography variant="body1">Subscription Details</Typography>
          <Stack alignItems="center">
            <Typography variant="h6">
              <AmountDisplay amount={subscription.price.amount} currency={'CC'} /> every 30 days
            </Typography>
            <Typography variant="body2">
              <AmountDisplay amount={usd.toString()} currency={'USD'} /> @{' '}
              <AmountDisplay amount={exchangeRateUSDToCC.toString()} currency={'CC'} />
              /USD
            </Typography>
          </Stack>
          <Stack alignItems="center">
            <Typography variant="body2">The first payment will be deducted immediately.</Typography>
          </Stack>
          <Button variant="pill" size="large">
            Confirm Subscription
          </Button>
        </Stack>
      </Box>
    </Container>
  );
};
