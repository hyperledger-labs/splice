import * as React from 'react';
import BigNumber from 'bignumber.js';
import {
  AmountDisplay,
  Contract,
  DirectoryEntry,
  IntervalDisplay,
  sameContracts,
  useInterval,
} from 'common-frontend';
import differenceInMilliseconds from 'date-fns/differenceInMilliseconds';
import intlFormat from 'date-fns/intlFormat';
import parseISO from 'date-fns/parseISO';
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

import { SubscriptionPayData } from '@daml.js/wallet-payments-0.1.0/lib/CN/Wallet/Subscriptions';
import { Party } from '@daml/types';

import Loading from '../components/Loading';
import { useCoinPrice } from '../contexts/CoinPriceContext';
import { useWalletClient } from '../contexts/WalletServiceContext';
import { SubscriptionState, WalletSubscription } from '../models/models';
import { convertCurrency } from '../utils/currencyConversion';

const Subscriptions: React.FC = () => {
  const { listSubscriptions, cancelSubscription } = useWalletClient();
  const [subscriptionTuples, setSubscriptionTuples] = useState<WalletSubscription[]>([]);

  const fetchSubscriptions = useCallback(async () => {
    const { subscriptionsList } = await listSubscriptions();
    setSubscriptionTuples(prev =>
      unchangedSubscriptionTuples(subscriptionsList, prev) ? prev : subscriptionsList
    );
  }, [listSubscriptions]);

  useInterval(fetchSubscriptions);

  const coinPrice = useCoinPrice();

  if (!coinPrice) {
    return <Loading />;
  }

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
              <TableCell>Service</TableCell>
              <TableCell align="right">Price</TableCell>
              <TableCell>Payment Due</TableCell>
              <TableCell>Actions</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {subscriptionTuples.map((subscription, index) => {
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
      <PartyCell className="sub-receiver">
        <DirectoryEntry partyId={subscription.subscription.payload.receiver} />
      </PartyCell>
      <PartyCell>
        <Service
          provider={subscription.subscription.payload.provider}
          description={subscription.context.payload.description}
        />
      </PartyCell>
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

const PartyCell = styled(TableCell)({
  overflow: 'hidden',
  textOverflow: 'ellipsis',
});

const Service: React.FC<{ provider: Party; description: string }> = ({ provider, description }) => {
  return (
    <Stack>
      <Typography variant="h6" className="sub-description">
        {description}
      </Typography>
      <Typography variant="caption" className="sub-provider">
        <DirectoryEntry partyId={provider} />
      </Typography>
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

const unchangedSubscriptionTuples = (a: WalletSubscription[], b: WalletSubscription[]): boolean => {
  return (
    sameContracts(
      a.map(x => x.subscription),
      b.map(x => x.subscription)
    ) &&
    sameContracts(
      a.map(x => x.state.value as Contract<unknown>),
      b.map(x => x.state.value as Contract<unknown>)
    ) &&
    sameContracts(
      a.map(x => x.context),
      b.map(x => x.context)
    )
  );
};
export default Subscriptions;
