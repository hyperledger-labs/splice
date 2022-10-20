import { DirectoryEntry, sameContracts, useInterval, Contract } from 'common-frontend';
import React, { useCallback, useState } from 'react';

import { Button, Stack, Table, TableBody, TableCell, TableHead, TableRow } from '@mui/material';

import { AppMultiPaymentRequest, ReceiverQuantity } from '@daml.js/wallet/lib/CN/Wallet';

import { useWalletClient } from '../contexts/WalletServiceContext';

// TODO(i1196) Improve multi-party settlement control
const AppMultiPaymentRequests: React.FC = () => {
  const { listAppMultiPaymentRequests, acceptAppMultiPaymentRequests } = useWalletClient();

  const [appPaymentRequests, setAppMultiPaymentRequests] = useState<
    Contract<AppMultiPaymentRequest>[]
  >([]);
  const fetchAppMultiPaymentRequests = useCallback(async () => {
    const { paymentRequestsList } = await listAppMultiPaymentRequests();
    setAppMultiPaymentRequests(prev =>
      sameContracts(paymentRequestsList, prev) ? prev : paymentRequestsList
    );
  }, [listAppMultiPaymentRequests, setAppMultiPaymentRequests]);

  useInterval(fetchAppMultiPaymentRequests, 500);

  const Request: React.FC<{ request: ReceiverQuantity; provider: string; cid: string }> = ({
    request,
    provider,
    cid,
  }) => {
    return (
      <TableRow className="app-requests-table-row">
        <TableCell className="app-request-receiver">
          <DirectoryEntry partyId={request.receiver} />
        </TableCell>
        <TableCell>{request.quantity}</TableCell>
        <TableCell className="app-request-provider">
          <DirectoryEntry partyId={provider} />
        </TableCell>
        <TableCell>
          <Button type="submit" onClick={() => acceptAppMultiPaymentRequests(cid)}>
            Accept
          </Button>
        </TableCell>
      </TableRow>
    );
  };

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
          {appPaymentRequests.flatMap(c =>
            c.payload.receiverQuantities.map(rc => (
              <Request
                request={rc}
                provider={c.payload.provider}
                cid={c.contractId}
                key={c.contractId}
              />
            ))
          )}
        </TableBody>
      </Table>
    </Stack>
  );
};

export default AppMultiPaymentRequests;
