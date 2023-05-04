import BigNumber from 'bignumber.js';
import { AmountDisplay, DirectoryEntry, IntervalDisplay } from 'common-frontend';
import Loading from 'common-frontend/lib/components/Loading';
import React, { useEffect, useState } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';

import { Box, Button, Container, Stack, Typography } from '@mui/material';

import { SubscriptionRequest as damlSubscriptionRequest } from '@daml.js/wallet-payments-0.1.0/lib/CN/Wallet/Subscriptions';
import { ContractId } from '@daml/types';

import { useWalletClient } from '../contexts/WalletServiceContext';
import { useCoinPrice } from '../hooks';
import { SubscriptionRequestWithContext } from '../models/models';
import { convertCurrency } from '../utils/currencyConversion';

export const ConfirmSubscription: React.FC = () => {
  const { cid } = useParams();
  const { getSubscriptionRequest } = useWalletClient();

  const [subscriptionRequest, setSubscriptionRequest] = useState<SubscriptionRequestWithContext>();
  useEffect(() => {
    const fetchSubscriptionRequest = async () => {
      const subscriptionRequest = await getSubscriptionRequest(cid!);
      setSubscriptionRequest(subscriptionRequest);
    };
    fetchSubscriptionRequest();
  }, [cid, getSubscriptionRequest]);

  if (!subscriptionRequest) {
    return <Loading />;
  }

  return (
    <Container maxWidth="md">
      <Stack alignItems="center" paddingTop={4} spacing={4}>
        <Stack alignItems="center" spacing={1}>
          <Stack alignItems="center" direction="row" spacing={1}>
            <Typography variant="h5">Confirm Subscription to </Typography>
            <DirectoryEntry
              partyId={subscriptionRequest.subscriptionRequest.payload.subscriptionData.receiver}
              variant="h5"
            />
          </Stack>
          <Stack alignItems="center" direction="row" spacing={1}>
            <Typography variant="body2">via </Typography>
            <DirectoryEntry
              partyId={subscriptionRequest.subscriptionRequest.payload.subscriptionData.provider}
              variant="body2"
            />
          </Stack>
        </Stack>
        <SubscriptionContainer subscription={subscriptionRequest} />
      </Stack>
    </Container>
  );
};

export default ConfirmSubscription;

const SubscriptionContainer: React.FC<{ subscription: SubscriptionRequestWithContext }> = ({
  subscription,
}) => {
  const coinPriceQuery = useCoinPrice();

  if (coinPriceQuery.isLoading) {
    return <Loading />;
  }

  // TODO(#4139) implement error state from design
  if (coinPriceQuery.isError) {
    return <p>Error, something went wrong.</p>;
  }

  const payData = subscription.subscriptionRequest.payload.payData;
  const amount = new BigNumber(payData.paymentAmount.amount);
  const currency = payData.paymentAmount.currency;
  const converted = convertCurrency(amount, currency, coinPriceQuery.data);

  return (
    <Container maxWidth="xl">
      <Box bgcolor="colors.neutral.20" border={1} borderColor="colors.neutral.30">
        <Stack alignItems="center" spacing={4} marginY={4}>
          <Stack alignItems="center">
            <Typography variant="body1">Description</Typography>
            <Typography variant="h6" className="sub-request-description">
              {subscription.context.payload.description}
            </Typography>
          </Stack>
          <Typography variant="body1">Subscription Details</Typography>
          <Stack alignItems="center">
            <Typography variant="h6" className="sub-request-price">
              <AmountDisplay amount={amount} currency={currency} /> per{' '}
              <IntervalDisplay microseconds={payData.paymentInterval.microseconds} />
            </Typography>
            <Typography variant="body2" className="sub-request-price-converted">
              <AmountDisplay amount={converted.amount} currency={converted.currency} /> @{' '}
              <AmountDisplay amount={coinPriceQuery.data} currency={currency} />/
              {converted.currency}
            </Typography>
            <Typography variant="body2">Fees will be added.</Typography>
          </Stack>
          <Stack alignItems="center">
            <Typography variant="body2">The first payment will be deducted immediately.</Typography>
          </Stack>
          <ConfirmSubscriptionButton cid={subscription.subscriptionRequest.contractId} />
        </Stack>
      </Box>
    </Container>
  );
};

const ConfirmSubscriptionButton: React.FC<{ cid: ContractId<damlSubscriptionRequest> }> = ({
  cid,
}) => {
  const { acceptSubscriptionRequest } = useWalletClient();
  const [searchParams] = useSearchParams();
  const redirect = searchParams.get('redirect');

  const onAccept = async () => {
    await acceptSubscriptionRequest(cid);
    if (redirect) {
      window.location.assign(redirect);
    }
  };

  return (
    <Button variant="pill" size="large" onClick={onAccept} className="sub-request-accept-button">
      Confirm Subscription
    </Button>
  );
};
