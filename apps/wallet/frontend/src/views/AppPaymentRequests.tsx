import { DirectoryEntry, sameContracts, useInterval, Contract } from 'common-frontend';
import React, { useCallback, useState } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';

import {
  Button,
  Collapse,
  IconButton,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
} from '@mui/material';

import { AppPaymentRequest } from '@daml.js/wallet-payments/lib/CN/Wallet/Payment';

import { useWalletClient } from '../contexts/WalletServiceContext';

const AppPaymentRequests: React.FC = () => {
  const { listAppPaymentRequests, acceptAppPaymentRequests } = useWalletClient();
  const { cid } = useParams();
  const [searchParams] = useSearchParams();

  const [appPaymentRequests, setAppPaymentRequests] = useState<Contract<AppPaymentRequest>[]>([]);
  const fetchAppPaymentRequests = useCallback(async () => {
    const { paymentRequestsList } = await listAppPaymentRequests();
    const filteredReqs = () => {
      if (!cid) return paymentRequestsList;
      else return paymentRequestsList.filter(c => c.contractId === cid);
    };
    setAppPaymentRequests(prev => (sameContracts(filteredReqs(), prev) ? prev : filteredReqs()));
  }, [listAppPaymentRequests, setAppPaymentRequests, cid]);
  useInterval(fetchAppPaymentRequests, 500);

  const Request: React.FC<{ request: AppPaymentRequest; provider: string; cid: string }> = ({
    request,
    provider,
    cid,
  }) => {
    const onAccept = async (cid: string) => {
      await acceptAppPaymentRequests(cid);
      const target = searchParams.get('redirect');
      if (target) {
        window.location.assign(target);
      }
    };

    const [visible, setVisible] = useState(true);

    return (
      <>
        <TableRow className="app-requests-table-row">
          <TableCell>
            <IconButton aria-label="expand-row" size="small" onClick={() => setVisible(!visible)}>
              {visible ? '↓' : '→'}
            </IconButton>
          </TableCell>
          <TableCell className="app-request-provider">
            <DirectoryEntry partyId={provider} />
          </TableCell>
          <TableCell />
          <TableCell>
            <Button className="accept-button" type="submit" onClick={() => onAccept(cid)}>
              Accept
            </Button>
          </TableCell>
        </TableRow>
        <TableRow className="app-request-breakdown-table-row">
          <TableCell style={{ paddingBottom: 0, paddingTop: 0 }} colSpan={3}>
            <Collapse in={visible}>
              <Table size="small" aria-label="receivers">
                <TableHead>
                  <TableRow>
                    <TableCell>Receiver</TableCell>
                    <TableCell align="right">Quantity</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {request.receiverQuantities.map(({ receiver, quantity }, index) => (
                    <TableRow key={index}>
                      <TableCell className="app-request-receiver">
                        <DirectoryEntry partyId={receiver} />
                      </TableCell>
                      <TableCell align="right">
                        {quantity.quantity}
                        {quantity.currency}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </Collapse>
          </TableCell>
        </TableRow>
      </>
    );
  };

  return (
    <Stack spacing={2}>
      <Table>
        <TableHead>
          <TableRow>
            <TableCell />
            <TableCell>Provider</TableCell>
            <TableCell />
          </TableRow>
        </TableHead>
        <TableBody>
          {appPaymentRequests.map(c => (
            <Request
              request={c.payload}
              provider={c.payload.provider}
              cid={c.contractId}
              key={c.contractId}
            />
          ))}
        </TableBody>
      </Table>
    </Stack>
  );
};

export default AppPaymentRequests;
