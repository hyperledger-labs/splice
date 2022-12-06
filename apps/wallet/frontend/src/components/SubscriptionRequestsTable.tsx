import { Contract, DirectoryEntry } from 'common-frontend';
import React from 'react';
import { useSearchParams } from 'react-router-dom';

import { Button, Table, TableBody, TableCell, TableHead, TableRow } from '@mui/material';

import { SubscriptionRequest as DamlSubscriptionRequest } from '@daml.js/wallet-payments/lib/CN/Wallet/Subscriptions';

import { useWalletClient } from '../contexts/WalletServiceContext';

interface SubscriptionsProps {
  requests: Contract<DamlSubscriptionRequest>[];
}

const SubscriptionRequestsTable: React.FC<SubscriptionsProps> = ({ requests }) => {
  return (
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
        {requests.map(c => (
          <SubscriptionRequest request={c} key={c.contractId} />
        ))}
      </TableBody>
    </Table>
  );
};

const SubscriptionRequest: React.FC<{ request: Contract<DamlSubscriptionRequest> }> = ({
  request,
}) => {
  const { acceptSubscriptionRequest } = useWalletClient();
  const [searchParams] = useSearchParams();

  const onAccept = async (cid: string) => {
    await acceptSubscriptionRequest(cid);
    const target = searchParams.get('redirect');
    if (target) {
      window.location.assign(target);
    }
  };

  return (
    <TableRow className="sub-requests-table-row">
      <TableCell className="sub-request-receiver">
        <DirectoryEntry partyId={request.payload.subscriptionData.receiver} />
      </TableCell>
      {/* TODO(#1641) Display currency */}
      <TableCell>{request.payload.payData.paymentQuantity.quantity}</TableCell>
      <TableCell>{request.payload.payData.paymentInterval.microseconds}</TableCell>
      <TableCell className="sub-request-provider">
        <DirectoryEntry partyId={request.payload.subscriptionData.provider} />
      </TableCell>
      <TableCell>
        <Button
          type="submit"
          className="sub-request-accept-button"
          onClick={() => onAccept(request.contractId)}
        >
          Accept and Pay
        </Button>
      </TableCell>
    </TableRow>
  );
};

export default SubscriptionRequestsTable;
