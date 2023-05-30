import * as React from 'react';
import BigNumber from 'bignumber.js';
import {
  AmountDisplay,
  DirectoryEntry,
  ErrorDisplay,
  IntervalDisplay,
  Loading,
} from 'common-frontend';
import { useCoinPrice } from 'common-frontend/scan-api';
import differenceInMilliseconds from 'date-fns/differenceInMilliseconds';
import intlFormat from 'date-fns/intlFormat';
import parseISO from 'date-fns/parseISO';

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

import { SubscriptionPayData } from '@daml.js/wallet-payments-0.1.0/lib/CN/Wallet/Subscriptions';
import { Party } from '@daml/types';

import { useWalletClient } from '../contexts/WalletServiceContext';
import { useSubscriptions } from '../hooks';
import { SubscriptionState, WalletSubscription } from '../models/models';
import { convertCurrency } from '../utils/currencyConversion';

const Subscriptions: React.FC = () => {
  const { cancelSubscription } = useWalletClient();
  const subscriptionQuery = useSubscriptions();
  const coinPriceQuery = useCoinPrice();

  const isLoading = coinPriceQuery.isLoading || subscriptionQuery.isLoading;
  const isError = coinPriceQuery.isError || subscriptionQuery.isError;

  return (
    <Stack marginY={10} spacing={2}>
      <Typography variant="h6" fontWeight="bold">
        Your Subscriptions
      </Typography>
      {isLoading ? (
        <Loading />
      ) : isError ? (
        <ErrorDisplay message={'Error while fetching either transactions or coin price.'} />
      ) : (
        <TableContainer>
          <Table style={{ tableLayout: 'fixed' }}>
            <TableHead>
              <TableRow>
                <TableCell>Receiver</TableCell>
                <TableCell>Service</TableCell>
                <TableCell align="right">Price</TableCell>
                <TableCell>Payment Due</TableCell>
                <TableCell>Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {subscriptionQuery.data.map((subscription, index) => {
                const onCancel = () => {
                  cancelSubscription(subscription.state.value.contractId).catch(err =>
                    console.error('Failed to cancel subscription.', err)
                  );
                };

                return (
                  <SubscriptionRow
                    key={subscription.subscription.contractId}
                    subscription={subscription}
                    cancelSubscription={onCancel}
                    coinPrice={coinPriceQuery.data}
                  />
                );
              })}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </Stack>
  );
};

interface SubscriptionRowProps {
  subscription: WalletSubscription;
  cancelSubscription: () => void;
  coinPrice: BigNumber;
}
const SubscriptionRow: React.FC<SubscriptionRowProps> = ({
  subscription,
  cancelSubscription,
  coinPrice,
}) => {
  return (
    <TableRow className="subscription-row">
      <TableCell variant="party" className="sub-receiver">
        <DirectoryEntry partyId={subscription.subscription.payload.receiver} />
      </TableCell>
      <TableCell variant="party">
        <Service
          provider={subscription.subscription.payload.provider}
          description={subscription.context.payload.description}
        />
      </TableCell>
      <TableCell align="right">
        <Price payData={subscription.state.value.payload.payData} coinPrice={coinPrice} />
      </TableCell>
      <TableCell>
        <PaymentDue state={subscription.state} />
      </TableCell>
      <TableCell>
        <Button
          className="sub-cancel-button"
          variant="pill"
          size="small"
          onClick={cancelSubscription}
          disabled={subscription.state.type !== 'idle'}
        >
          Cancel
        </Button>
      </TableCell>
    </TableRow>
  );
};

const Service: React.FC<{ provider: Party; description: string }> = ({ provider, description }) => {
  return (
    <Stack>
      <Typography variant="h6" className="sub-description">
        {description}
      </Typography>
      <DirectoryEntry partyId={provider} variant="caption" classNames="sub-provider" />
    </Stack>
  );
};

interface PriceProps {
  payData: SubscriptionPayData;
  coinPrice: BigNumber;
}
const Price: React.FC<PriceProps> = ({ payData, coinPrice }) => {
  const amount = new BigNumber(payData.paymentAmount.amount);
  const currency = payData.paymentAmount.currency;
  const perPeriod = payData.paymentInterval;
  const converted = convertCurrency(amount, currency, coinPrice);

  return (
    <Stack>
      <Box
        display="flex"
        flexDirection="row"
        justifyContent="flex-end"
        alignItems="baseline"
        gap={1}
        className="sub-price"
      >
        <Typography variant="h6">
          <AmountDisplay amount={amount} currency={currency} />
        </Typography>
        per <IntervalDisplay microseconds={perPeriod.microseconds} />
      </Box>
      <Typography variant="caption" className="sub-coin-price">
        <AmountDisplay amount={converted.amount} currency={converted.currency} /> @{' '}
        {converted.coinPriceToShow.toString()}
        {currency}/{converted.currency}
      </Typography>
    </Stack>
  );
};

const PaymentDue: React.FC<{ state: SubscriptionState }> = ({ state }) => {
  let paymentDueAtString: string;
  switch (state.type) {
    case 'idle':
      paymentDueAtString = state.value.payload.nextPaymentDueAt;
      break;
    case 'payment':
      paymentDueAtString = state.value.payload.thisPaymentDueAt;
      break;
  }
  const paymentDueAt = parseISO(paymentDueAtString);
  const now = new Date();
  const millisecondsLeft = differenceInMilliseconds(paymentDueAt, now);
  return (
    <Stack>
      <Typography variant="h6" className="sub-payment-due">
        <IntervalDisplay microseconds={(millisecondsLeft * 1000).toString()} />
      </Typography>
      <Typography variant="caption" className="sub-payment-date">
        {intlFormat(paymentDueAt)}
      </Typography>
    </Stack>
  );
};

export default Subscriptions;
