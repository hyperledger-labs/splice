import { Contract } from 'common-frontend';
import React, { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';

import { Stack, Typography } from '@mui/material';

import { SubscriptionRequest as damlSubscriptionRequest } from '@daml.js/wallet-payments-0.1.0/lib/CN/Wallet/Subscriptions';

import SubscriptionRequestsTable from '../components/SubscriptionRequestsTable';
import { useWalletClient } from '../contexts/WalletServiceContext';

const ConfirmSubscription: React.FC = () => {
  const { listSubscriptionRequests } = useWalletClient();
  const { cid } = useParams();
  const [subscriptionRequest, setSubscriptionRequest] =
    useState<Contract<damlSubscriptionRequest>>();
  useEffect(() => {
    const fetchSubscriptionRequest = async () => {
      const { subscriptionRequestsList } = await listSubscriptionRequests();
      const req = subscriptionRequestsList.find(c => c.contractId === cid);
      if (!req) {
        throw new Error('Subscription request contract not found');
      }
      setSubscriptionRequest(req);
    };
    fetchSubscriptionRequest();
  }, [cid, listSubscriptionRequests]);

  if (subscriptionRequest === undefined || cid === undefined) {
    return <div>Loading...</div>;
  }
  return (
    <Stack>
      <Typography variant="h6">Please accept the following subscription request:</Typography>
      <SubscriptionRequestsTable requests={[subscriptionRequest]} />
    </Stack>
  );
};

export default ConfirmSubscription;
