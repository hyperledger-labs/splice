import { DirectoryEntry, sameContracts, useInterval, Contract } from 'common-frontend';
import {
  AcceptAppMultiPaymentRequestRequest,
  ListAppMultiPaymentRequestsRequest,
  WalletContext,
} from 'common-protobuf/com/daml/network/wallet/v0/wallet_service_pb';
import React, { useCallback, useState } from 'react';

import { Button, Stack, Table, TableBody, TableCell, TableHead, TableRow } from '@mui/material';

import { AppMultiPaymentRequest, ReceiverQuantity } from '@daml.js/wallet/lib/CN/Wallet';

import { useWalletClient } from '../contexts/WalletServiceContext';

// TODO(i1196) Improve multi-party settlement control
const AppMultiPaymentRequests: React.FC<{ userId: string }> = ({ userId }) => {
  const walletClient = useWalletClient();
  const walletRequestCtx = new WalletContext().setUserName(userId);

  const [appPaymentRequests, setAppMultiPaymentRequests] = useState<
    Contract<AppMultiPaymentRequest>[]
  >([]);
  const fetchAppMultiPaymentRequests = useCallback(async () => {
    const newAppMultiPaymentRequests = (
      await walletClient.listAppMultiPaymentRequests(
        new ListAppMultiPaymentRequestsRequest().setWalletCtx(walletRequestCtx),
        undefined
      )
    ).getPaymentRequestsList();
    const decoded = newAppMultiPaymentRequests.map(c => Contract.decode(c, AppMultiPaymentRequest));
    setAppMultiPaymentRequests(prev => (sameContracts(decoded, prev) ? prev : decoded));
  }, [walletClient, walletRequestCtx, setAppMultiPaymentRequests]);
  useInterval(fetchAppMultiPaymentRequests, 500);

  const Request: React.FC<{ request: ReceiverQuantity; provider: string; cid: string }> = ({
    request,
    provider,
    cid,
  }) => {
    const onAccept = async () => {
      await walletClient.acceptAppMultiPaymentRequest(
        new AcceptAppMultiPaymentRequestRequest()
          .setRequestContractId(cid)
          .setWalletCtx(walletRequestCtx),
        undefined
      );
    };
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
          <Button type="submit" onClick={onAccept}>
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
