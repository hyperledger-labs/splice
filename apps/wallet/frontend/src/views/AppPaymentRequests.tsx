import { DirectoryEntry, sameContracts, useInterval, Contract } from 'common-frontend';
import React, { useCallback, useState } from 'react';

import { Button, Stack, Table, TableBody, TableCell, TableHead, TableRow } from '@mui/material';

import { AppPaymentRequest } from '@daml.js/wallet/lib/CN/Wallet';

import { useWalletClient } from '../contexts/WalletServiceContext';

const AppPaymentRequests: React.FC = () => {
  const { listAppPaymentRequests, acceptAppPaymentRequest } = useWalletClient();
  const [appPaymentRequests, setAppPaymentRequests] = useState<Contract<AppPaymentRequest>[]>([]);

  const fetchAppPaymentRequests = useCallback(async () => {
    const { paymentRequestsList } = await listAppPaymentRequests();
    setAppPaymentRequests(prev =>
      sameContracts(paymentRequestsList, prev) ? prev : paymentRequestsList
    );
  }, [listAppPaymentRequests, setAppPaymentRequests]);

  useInterval(fetchAppPaymentRequests, 500);

  const Request: React.FC<{ request: Contract<AppPaymentRequest> }> = ({ request }) => (
    <TableRow className="app-requests-table-row">
      <TableCell className="app-request-receiver">
        <DirectoryEntry partyId={request.payload.receiver} />
      </TableCell>
      <TableCell>{request.payload.quantity}</TableCell>
      <TableCell className="app-request-provider">
        <DirectoryEntry partyId={request.payload.provider} />
      </TableCell>
      <TableCell>
        <Button type="submit" onClick={() => acceptAppPaymentRequest(request.contractId)}>
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
            <TableCell>Quantity</TableCell>
            <TableCell>Provider</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {appPaymentRequests.map(c => (
            <Request request={c} key={c.contractId} />
          ))}
        </TableBody>
      </Table>
    </Stack>
  );
};

export default AppPaymentRequests;
