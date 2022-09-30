import React, { useCallback, useState } from 'react';

import { Button, Stack, Table, TableBody, TableCell, TableHead, TableRow } from '@mui/material';

import { AppPaymentRequest } from '@daml.js/wallet/lib/CN/Wallet';

import {
  AcceptAppPaymentRequestRequest,
  ListAppPaymentRequestsRequest,
  WalletContext,
} from '../com/daml/network/wallet/v0/wallet_service_pb';
import { useWalletClient } from '../contexts/WalletServiceContext';
import { sameContracts, useInterval, Contract } from '../utils';

const AppPaymentRequests: React.FC<{ userId: string }> = ({ userId }) => {
  const walletClient = useWalletClient();
  const walletRequestCtx = new WalletContext().setUserId(userId);

  const [appPaymentRequests, setAppPaymentRequests] = useState<Contract<AppPaymentRequest>[]>([]);
  const fetchAppPaymentRequests = useCallback(async () => {
    const newAppPaymentRequests = (
      await walletClient.listAppPaymentRequests(
        new ListAppPaymentRequestsRequest().setWalletCtx(walletRequestCtx),
        null
      )
    ).getPaymentRequestsList();
    const decoded = newAppPaymentRequests.map(c => Contract.decode(c, AppPaymentRequest));
    setAppPaymentRequests(prev => (sameContracts(decoded, prev) ? prev : decoded));
  }, [walletClient, walletRequestCtx, setAppPaymentRequests]);
  useInterval(fetchAppPaymentRequests, 500);

  const Request: React.FC<{ request: Contract<AppPaymentRequest> }> = ({ request }) => {
    const onAccept = async () => {
      await walletClient.acceptAppPaymentRequest(
        new AcceptAppPaymentRequestRequest()
          .setRequestContractId(request.contractId)
          .setWalletCtx(walletRequestCtx),
        null
      );
    };
    return (
      <TableRow>
        <TableCell>{request.payload.receiver}</TableCell>
        <TableCell>{request.payload.quantity}</TableCell>
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
