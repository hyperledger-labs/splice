// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  AmountDisplay,
  Loading,
  ErrorDisplay,
  IntervalDisplay,
  DisableConditionally,
  unitToCurrency,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import BigNumber from 'bignumber.js';
import { useState } from 'react';
import { useParams, useSearchParams } from 'react-router';

import { Box, Button, Container, Stack, Typography } from '@mui/material';

import {
  SubscriptionRequest,
  SubscriptionRequest as damlSubscriptionRequest,
} from '@daml.js/splice-wallet-payments/lib/Splice/Wallet/Subscriptions';
import { ContractId } from '@daml/types';

import BftAnsEntry from '../components/BftAnsEntry';
import { useWalletClient } from '../contexts/WalletServiceContext';
import { useSubscriptionRequest } from '../hooks';
import useAmuletPrice from '../hooks/scan-proxy/useAmuletPrice';
import { convertCurrency } from '../utils/currencyConversion';

export const ConfirmSubscription: React.FC = () => {
  const { cid } = useParams();
  const subscriptionRequestQuery = useSubscriptionRequest(cid!);
  const isDataUndefined = subscriptionRequestQuery.data === undefined;

  if (subscriptionRequestQuery.isLoading) {
    return <Loading />;
  }

  return (
    <Container maxWidth="md">
      <Stack alignItems="center" paddingTop={4} spacing={4}>
        {subscriptionRequestQuery.isError || isDataUndefined ? (
          <Box display="flex" alignItems="center" justifyContent="center">
            <ErrorDisplay message={'Error while fetching subscription request and amulet price'} />
          </Box>
        ) : (
          <>
            <Stack alignItems="center" spacing={1}>
              <Stack alignItems="center" direction="row" spacing={1}>
                <Typography variant="h6">Confirm Subscription to </Typography>
                <BftAnsEntry
                  partyId={subscriptionRequestQuery.data.payload.subscriptionData.receiver}
                  variant="h5"
                />
              </Stack>
              <Stack alignItems="center" direction="row" spacing={1}>
                <Typography variant="body2">via </Typography>
                <BftAnsEntry
                  partyId={subscriptionRequestQuery.data.payload.subscriptionData.provider}
                  variant="body2"
                />
              </Stack>
            </Stack>
            <SubscriptionContainer subscription={subscriptionRequestQuery.data} />
          </>
        )}
      </Stack>
    </Container>
  );
};

export default ConfirmSubscription;

const SubscriptionContainer: React.FC<{ subscription: Contract<SubscriptionRequest> }> = ({
  subscription,
}) => {
  const amuletPriceQuery = useAmuletPrice();

  if (amuletPriceQuery.isLoading) {
    return <Loading />;
  }

  if (amuletPriceQuery.isError || amuletPriceQuery.data === undefined) {
    return <ErrorDisplay message={'Error while fetching amulet price'} />;
  }

  const payData = subscription.payload.payData;
  const amount = new BigNumber(payData.paymentAmount.amount);
  const unit = payData.paymentAmount.unit;
  const converted = convertCurrency(amount, unit, amuletPriceQuery.data);

  return (
    <Container maxWidth="xl">
      <Box bgcolor="colors.neutral.10" border={1} borderColor="colors.neutral.15">
        <Stack alignItems="center" spacing={4} marginY={4}>
          <Stack alignItems="center">
            <Typography variant="body1">Description</Typography>
            <Typography variant="h6" className="sub-request-description">
              {subscription.payload.subscriptionData.description}
            </Typography>
          </Stack>
          <Typography variant="body1">Subscription Details</Typography>
          <Stack alignItems="center">
            <Typography variant="h6" className="sub-request-price">
              <AmountDisplay amount={amount} currency={unit} /> per{' '}
              <IntervalDisplay microseconds={payData.paymentInterval.microseconds} />
            </Typography>
            <Typography variant="body2" className="sub-request-price-converted">
              <AmountDisplay amount={converted.amount} currency={converted.currency} /> @{' '}
              <AmountDisplay amount={amuletPriceQuery.data} currency={unit} />/
              {unitToCurrency(converted.currency)}
            </Typography>
            <Typography variant="body2">Fees will be added.</Typography>
          </Stack>
          <Stack alignItems="center">
            <Typography variant="body2">The first payment will be deducted immediately.</Typography>
          </Stack>
          <ConfirmSubscriptionButton cid={subscription.contractId} />
        </Stack>
      </Box>
    </Container>
  );
};

const ConfirmSubscriptionButton: React.FC<{ cid: ContractId<damlSubscriptionRequest> }> = ({
  cid,
}) => {
  const [clicked, setClicked] = useState(false);
  const { acceptSubscriptionRequest } = useWalletClient();
  const [searchParams] = useSearchParams();
  const redirect = searchParams.get('redirect');

  const onAccept = async () => {
    setClicked(true);
    await acceptSubscriptionRequest(cid);
    if (redirect) {
      window.location.assign(redirect);
    }
  };

  return (
    <DisableConditionally conditions={[{ disabled: clicked, reason: 'Loading...' }]}>
      <Button variant="pill" size="large" onClick={onAccept} className="sub-request-accept-button">
        Confirm Subscription
      </Button>
    </DisableConditionally>
  );
};
