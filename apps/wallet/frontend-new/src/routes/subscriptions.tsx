import * as React from 'react';
import {
  AmountDisplay,
  Contract,
  DirectoryEntry,
  IntervalDisplay,
  sameContracts,
  useInterval,
  useScanClient,
} from 'common-frontend';
import differenceInMilliseconds from 'date-fns/differenceInMilliseconds';
import intlFormat from 'date-fns/intlFormat';
import parseISO from 'date-fns/parseISO';
import { Decimal } from 'decimal.js';
import { useCallback, useState } from 'react';

import {
  Box,
  Button,
  Stack,
  styled,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';

import {
  Subscription,
  SubscriptionPayData,
} from '@daml.js/wallet-payments-0.1.0/lib/CN/Wallet/Subscriptions';
import { Party } from '@daml/types';

import { useWalletClient } from '../contexts/WalletServiceContext';
import { SubscriptionState, SubscriptionTuple } from '../models/models';

const Subscriptions: React.FC = () => {
  const { listSubscriptions, cancelSubscription } = useWalletClient();
  const [subscriptionTuples, setSubscriptionTuples] = useState<SubscriptionTuple[]>([]);

  const fetchSubscriptions = useCallback(async () => {
    const { subscriptionsList } = await listSubscriptions();
    setSubscriptionTuples(prev =>
      unchangedSubscriptionTuples(subscriptionsList, prev) ? prev : subscriptionsList
    );
  }, [listSubscriptions]);

  useInterval(fetchSubscriptions, 500);

  // TODO (#3332): remove and fetch from context once it's refactored
  const scanClient = useScanClient();
  const [coinPrice, setCoinPrice] = useState<Decimal>(new Decimal(0));
  const fetchCoinPrice = useCallback(async () => {
    const coinPrice = await scanClient.getCoinPrice();
    // avoid unnecessary re-renders everytime the coin price is fetched but does not change.
    setCoinPrice(prevCoinPrice => (prevCoinPrice?.equals(coinPrice) ? prevCoinPrice : coinPrice));
  }, [scanClient]);

  useInterval(fetchCoinPrice, 1000);

  return (
    <Stack marginY={10} spacing={2}>
      <Typography variant="h6" fontWeight="bold">
        Your Subscriptions
      </Typography>
      <TableContainer>
        <Table style={{ tableLayout: 'fixed' }}>
          <TableHead>
            <TableRow>
              <TableCell>Receiver</TableCell>
              <TableCell>Provider</TableCell>
              <TableCell align="right">Price</TableCell>
              <TableCell>Payment Due</TableCell>
              <TableCell>Actions</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {subscriptionTuples.map(([subscription, state], index) => {
              const onCancel = () => {
                cancelSubscription(state.value.contractId).catch(err =>
                  console.error('Failed to cancel subscription.', err)
                );
              };

              return (
                <SubscriptionRow
                  key={subscription.contractId}
                  subscription={subscription}
                  state={state}
                  cancelSubscription={onCancel}
                  coinPrice={coinPrice}
                />
              );
            })}
          </TableBody>
        </Table>
      </TableContainer>
    </Stack>
  );
};

interface SubscriptionRowProps {
  subscription: Contract<Subscription>;
  state: SubscriptionState;
  cancelSubscription: () => void;
  coinPrice: Decimal;
}
const SubscriptionRow: React.FC<SubscriptionRowProps> = ({
  subscription,
  state,
  cancelSubscription,
  coinPrice,
}) => {
  return (
    <TableRow className="subscription-row">
      <PartyCell className="sub-receiver">
        <DirectoryEntry partyId={subscription.payload.receiver} />
      </PartyCell>
      <PartyCell>
        <Provider provider={subscription.payload.provider} />
      </PartyCell>
      <TableCell align="right">
        <Price payData={state.value.payload.payData} coinPrice={coinPrice} />
      </TableCell>
      <TableCell>
        <PaymentDue state={state} />
      </TableCell>
      <TableCell>
        <Button
          className="sub-cancel-button"
          variant="pill"
          size="small"
          onClick={cancelSubscription}
          disabled={state.type !== 'idle'}
        >
          Cancel
        </Button>
      </TableCell>
    </TableRow>
  );
};

const PartyCell = styled(TableCell)({
  overflow: 'hidden',
  textOverflow: 'ellipsis',
});

const Provider: React.FC<{ provider: Party }> = ({ provider }) => {
  return (
    <Stack>
      <Typography variant="h6" className="sub-description">
        Service Desc.{/*TODO (#3304): include description in BE response*/}
      </Typography>
      <Typography variant="caption" className="sub-provider">
        <DirectoryEntry partyId={provider} />
      </Typography>
    </Stack>
  );
};

interface PriceProps {
  payData: SubscriptionPayData;
  coinPrice: Decimal;
}
const Price: React.FC<PriceProps> = ({ payData, coinPrice }) => {
  const amount = new Decimal(payData.paymentAmount.amount);
  const currency = payData.paymentAmount.currency;
  const perPeriod = payData.paymentInterval;

  // TODO: (#3065) or (#3064) - factor out if they're the same
  let converted;
  if (currency === 'CC') {
    converted = {
      amount: amount.mul(coinPrice),
      currency: 'USD',
      coinPriceToShow: new Decimal(1).div(coinPrice),
    };
  } else {
    converted = {
      amount: amount.div(coinPrice),
      currency: 'CC',
      coinPriceToShow: coinPrice, // already in USD/CC
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
        className="sub-price"
      >
        <Typography variant="h6">
          <AmountDisplay amount={amount.toString()} currency={currency} />
        </Typography>
        per <IntervalDisplay microseconds={perPeriod.microseconds} />
      </Box>
      <Typography variant="caption" className="sub-coin-price">
        <AmountDisplay amount={converted.amount.toString()} currency={converted.currency} /> @{' '}
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

const unchangedSubscriptionTuples = (a: SubscriptionTuple[], b: SubscriptionTuple[]): boolean => {
  return (
    sameContracts(
      a.map(x => x[0]),
      b.map(x => x[0])
    ) &&
    sameContracts(
      a.map(x => x[1].value as Contract<unknown>),
      b.map(x => x[1].value as Contract<unknown>)
    )
  );
};
export default Subscriptions;
