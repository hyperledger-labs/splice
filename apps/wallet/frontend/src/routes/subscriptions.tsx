// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import {
  AmountDisplay,
  DisableConditionally,
  ErrorDisplay,
  IntervalDisplay,
  Loading,
  unitToCurrency,
} from '@lfdecentralizedtrust/splice-common-frontend';
import BigNumber from 'bignumber.js';
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

import { SubscriptionPayData } from '@daml.js/splice-wallet-payments/lib/Splice/Wallet/Subscriptions';
import { Party } from '@daml/types';

import BftAnsEntry from '../components/BftAnsEntry';
import { useWalletClient } from '../contexts/WalletServiceContext';
import { useSubscriptions } from '../hooks';
import useAmuletPrice from '../hooks/scan-proxy/useAmuletPrice';
import { SubscriptionState, WalletSubscription } from '../models/models';
import { convertCurrency } from '../utils/currencyConversion';

const Subscriptions: React.FC = () => {
  const { cancelSubscription } = useWalletClient();
  const subscriptionQuery = useSubscriptions();
  const amuletPriceQuery = useAmuletPrice();

  const isLoading = amuletPriceQuery.isLoading || subscriptionQuery.isLoading;
  const isError = amuletPriceQuery.isError || subscriptionQuery.isError;

  return (
    <Stack marginY={10} spacing={2}>
      <Typography variant="h6" fontWeight="bold">
        Your Subscriptions
      </Typography>
      {isLoading ? (
        <Loading />
      ) : isError ? (
        <ErrorDisplay message={'Error while fetching either transactions or amulet price.'} />
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
              {subscriptionQuery.data.map(subscription => {
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
                    amuletPrice={amuletPriceQuery.data}
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
  amuletPrice: BigNumber;
}
const SubscriptionRow: React.FC<SubscriptionRowProps> = ({
  subscription,
  cancelSubscription,
  amuletPrice,
}) => {
  return (
    <TableRow className="subscription-row">
      <TableCell variant="party">
        <BftAnsEntry
          className="sub-receiver"
          partyId={subscription.subscription.payload.subscriptionData.receiver}
        />
      </TableCell>
      <TableCell variant="party">
        <Service
          provider={subscription.subscription.payload.subscriptionData.provider}
          description={subscription.subscription.payload.subscriptionData.description}
        />
      </TableCell>
      <TableCell align="right">
        <Price payData={subscription.state.value.payload.payData} amuletPrice={amuletPrice} />
      </TableCell>
      <TableCell>
        <PaymentDue state={subscription.state} />
      </TableCell>
      <TableCell>
        <DisableConditionally
          conditions={[
            { disabled: subscription.state.type !== 'idle', reason: 'Subscription is not idle' },
          ]}
        >
          <Button
            className="sub-cancel-button"
            variant="pill"
            size="small"
            onClick={cancelSubscription}
          >
            Cancel
          </Button>
        </DisableConditionally>
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
      <BftAnsEntry partyId={provider} variant="caption" className="sub-provider" />
    </Stack>
  );
};

interface PriceProps {
  payData: SubscriptionPayData;
  amuletPrice: BigNumber;
}
const Price: React.FC<PriceProps> = ({ payData, amuletPrice }) => {
  const amount = new BigNumber(payData.paymentAmount.amount);
  const currency = payData.paymentAmount.unit;
  const perPeriod = payData.paymentInterval;
  const converted = convertCurrency(amount, currency, amuletPrice);

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
      <Typography variant="caption" className="sub-amulet-price">
        <AmountDisplay amount={converted.amount} currency={converted.currency} /> @{' '}
        {converted.amuletPriceToShow.toString()}
        {unitToCurrency(currency)}/{unitToCurrency(converted.currency)}
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
