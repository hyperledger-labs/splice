import { AmountDisplay } from 'common-frontend';
import React from 'react';

import { ArrowOutward } from '@mui/icons-material';
import { Box, Button, Container, Stack, styled, Typography } from '@mui/material';

import PaymentHeader from '../components/PaymentHeader';

export const ConfirmPayment: React.FC = () => {
  return (
    <Box display="flex" flexDirection="column" minHeight="100vh">
      <PaymentHeader />
      <Box bgcolor="colors.neutral.25" flex={1}>
        <Container maxWidth="sm">
          <Stack alignItems="center" paddingTop={4} spacing={4}>
            <RecipientInfo />
            <PaymentDescription />
            <PaymentContainer />
          </Stack>
        </Container>
      </Box>
    </Box>
  );
};

export default ConfirmPayment;

const RecipientInfo: React.FC = () => {
  const amount = 8.0;
  const recipient = 'credap-payments.cns';
  const via = 'credap.cns';
  return (
    <Stack alignItems="center" spacing={1}>
      <SendPaymentIcon />
      <Typography variant="h5">
        Send {amount} CC to <b>{recipient}</b>
      </Typography>
      <Typography variant="body2">via {via}</Typography>
    </Stack>
  );
};

const SendPaymentIcon = styled(ArrowOutward)({
  border: '1px solid #fff',
  borderRadius: '50%',
});

const PaymentDescription: React.FC = () => {
  const description = 'Trading credential for Alain Trading';
  return (
    <Stack alignItems="center">
      <Typography variant="body1">Description:</Typography>
      <Typography variant="body1">&quot;{description}&quot;</Typography>
    </Stack>
  );
};

const PaymentContainer: React.FC = () => {
  const amount = 8.0;
  const fee = 1.8;
  const usd = 130.12;
  const isFirstPaymentToRecipient = true;
  return (
    <Container>
      <Box bgcolor="colors.neutral.20" border={1} borderColor="colors.neutral.30">
        <Stack alignItems="center" spacing={4} marginY={4}>
          <Typography variant="body1">{"You'll pay:"}</Typography>
          <Stack alignItems="center">
            <Typography variant="h5">
              <AmountDisplay amount={(amount + fee).toString()} currency={'CC'} />
            </Typography>
            <Typography variant="body2">
              <AmountDisplay amount={amount.toString()} currency={'CC'} /> +{' '}
              <AmountDisplay amount={fee.toString()} currency={'CC'} /> fee /{' '}
              <AmountDisplay amount={usd.toString()} currency={'USD'} />
            </Typography>
          </Stack>
          <Button variant="pill" size="large">
            Send Payment
          </Button>
          {isFirstPaymentToRecipient && (
            <Typography variant="caption">
              {"You're sending a payment to this recipient for the first time."}
            </Typography>
          )}
        </Stack>
      </Box>
    </Container>
  );
};
