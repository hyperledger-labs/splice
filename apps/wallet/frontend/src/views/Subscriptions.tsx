import { DirectoryEntry, sameContracts, useInterval, Contract } from 'common-frontend';
import React, { useCallback, useState } from 'react';

import {
  Button,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';

import {
  SubscriptionRequest as damlSubscriptionRequest,
  Subscription,
} from '@daml.js/wallet-payments/lib/CN/Wallet/Subscriptions';

import SubscriptionRequestsTable from '../components/SubscriptionRequestsTable';
import {
  useWalletClient,
  SubscriptionTuple,
  SubscriptionState,
} from '../contexts/WalletServiceContext';

const Subscriptions: React.FC = () => {
  const { listSubscriptionRequests } = useWalletClient();
  const [SubscriptionRequests, setSubscriptionRequests] = useState<
    Contract<damlSubscriptionRequest>[]
  >([]);

  const fetchSubscriptionRequests = useCallback(async () => {
    const { subscriptionRequestsList } = await listSubscriptionRequests();
    const filteredReqs = () => {
      return subscriptionRequestsList;
    };
    setSubscriptionRequests(prev => (sameContracts(filteredReqs(), prev) ? prev : filteredReqs()));
  }, [listSubscriptionRequests, setSubscriptionRequests]);

  useInterval(fetchSubscriptionRequests, 500);

  return (
    <Stack spacing={2}>
      <Typography variant="h6">Active Subscription Requests</Typography>
      <SubscriptionRequestsTable requests={SubscriptionRequests} />
      <Typography variant="h6">Active Subscriptions</Typography>
      <SubscriptionsTable />
    </Stack>
  );
};

const SubscriptionsTable: React.FC = () => {
  const { listSubscriptions, cancelSubscription } = useWalletClient();
  const [SubscriptionTuples, setSubscriptionTuples] = useState<SubscriptionTuple[]>([]);

  const fetchSubscriptions = useCallback(async () => {
    const { subscriptionsList } = await listSubscriptions();
    setSubscriptionTuples(prev =>
      unchangedSubscriptionTuples(subscriptionsList, prev) ? prev : subscriptionsList
    );
  }, [listSubscriptions, setSubscriptionTuples]);

  useInterval(fetchSubscriptions, 500);

  const onCancel = async (state: SubscriptionState) => {
    if (state.type !== 'idle') {
      throw new Error('Cannot cancel a subscription which is not in idle state');
    }
    await cancelSubscription(state.value.contractId);
  };

  const Subscription: React.FC<{
    main: Contract<Subscription>;
    state: SubscriptionState;
  }> = ({ main, state }) => (
    <TableRow className="subs-table-row">
      <TableCell className="sub-receiver">
        <DirectoryEntry partyId={main.payload.receiver} />
      </TableCell>
      <TableCell>{state.value.payload.payData.paymentQuantity}</TableCell>
      <TableCell>{state.value.payload.payData.paymentInterval.microseconds}</TableCell>
      <TableCell>{paymentDueAt(state)}</TableCell>
      <TableCell className="sub-provider">
        <DirectoryEntry partyId={main.payload.provider} />
      </TableCell>
      <TableCell className="sub-state">{stateDescription(state)}</TableCell>
      <TableCell>
        <Button
          className="sub-cancel-button"
          onClick={() => onCancel(state)}
          disabled={state.type !== 'idle'}
        >
          Cancel Subscription
        </Button>
      </TableCell>
    </TableRow>
  );

  return (
    <Table>
      <TableHead>
        <TableRow>
          <TableCell>Receiver</TableCell>
          <TableCell>Payment quantity</TableCell>
          <TableCell>Payment interval (μs)</TableCell>
          <TableCell>Next payment due at</TableCell>
          <TableCell>Provider</TableCell>
          <TableCell>State</TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {SubscriptionTuples.map(t => (
          <Subscription main={t[0]} state={t[1]} key={t[0].contractId} />
        ))}
      </TableBody>
    </Table>
  );
};

const paymentDueAt = (state: SubscriptionState): string => {
  switch (state.type) {
    case 'idle':
      return state.value.payload.nextPaymentDueAt;
    case 'payment':
      return state.value.payload.thisPaymentDueAt;
  }
};

const stateDescription = (state: SubscriptionState): string => {
  switch (state.type) {
    case 'idle':
      return 'Waiting for next payment to become due';
    case 'payment':
      return 'Payment in progress';
  }
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
