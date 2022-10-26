import { DirectoryEntry, sameContracts, useInterval, Contract } from 'common-frontend';
import React, { useCallback, useState } from 'react';

import { Button, Stack, Table, TableBody, TableCell, TableHead, TableRow } from '@mui/material';

import { SubscriptionRequest } from '@daml.js/wallet/lib/CN/Wallet/Subscriptions';

import { useWalletClient } from '../contexts/WalletServiceContext';

const Subscriptions: React.FC = () => {
  const { listSubscriptionRequests, acceptSubscriptionRequest } = useWalletClient();
  const [SubscriptionRequests, setSubscriptions] = useState<Contract<SubscriptionRequest>[]>([]);

  const fetchSubscriptionRequests = useCallback(async () => {
    const { subscriptionRequestsList } = await listSubscriptionRequests();
    setSubscriptions(prev =>
      sameContracts(subscriptionRequestsList, prev) ? prev : subscriptionRequestsList
    );
  }, [listSubscriptionRequests, setSubscriptions]);

  useInterval(fetchSubscriptionRequests, 500);

  const Request: React.FC<{ request: Contract<SubscriptionRequest> }> = ({ request }) => (
    <TableRow className="sub-requests-table-row">
      <TableCell className="sub-request-receiver">
        <DirectoryEntry partyId={request.payload.subscriptionData.receiver} />
      </TableCell>
      <TableCell>{request.payload.payData.paymentQuantity}</TableCell>
      <TableCell>{request.payload.payData.paymentInterval.microseconds}</TableCell>
      <TableCell className="sub-request-provider">
        <DirectoryEntry partyId={request.payload.subscriptionData.provider} />
      </TableCell>
      <TableCell>
        <Button
          type="submit"
          className="sub-request-accept-button"
          onClick={() => acceptSubscriptionRequest(request.contractId)}
        >
          Accept
        </Button>
      </TableCell>
    </TableRow>
  );

  return (
    <Stack spacing={2}>
      <Table>
        <TableHead>
          <TableRow>
            <TableCell>Receiver</TableCell>
            <TableCell>Payment quantity</TableCell>
            <TableCell>Payment interval (μs)</TableCell>
            <TableCell>Provider</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {SubscriptionRequests.map(c => (
            <Request request={c} key={c.contractId} />
          ))}
        </TableBody>
      </Table>
    </Stack>
  );
};

export default Subscriptions;
