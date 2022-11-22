import { Contract, DirectoryEntry } from 'common-frontend';
import React, { useState } from 'react';
import { useSearchParams } from 'react-router-dom';

import {
  Button,
  Collapse,
  IconButton,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
} from '@mui/material';

import { AppPaymentRequest } from '@daml.js/wallet-payments-0.1.0/lib/CN/Wallet/Payment';

import { useWalletClient } from '../contexts/WalletServiceContext';
import { PaymentQuantityDisplay } from './QuantityDisplay';

interface AppPaymentRequestsProps {
  requests: Contract<AppPaymentRequest>[];
}

const AppPaymentRequestsTable: React.FC<AppPaymentRequestsProps> = ({ requests }) => {
  return (
    <Table>
      <TableHead>
        <TableRow>
          <TableCell />
          <TableCell>Provider</TableCell>
          <TableCell />
        </TableRow>
      </TableHead>
      <TableBody>
        {requests.map(c => (
          <AppPaymentRequestRows
            request={c.payload}
            provider={c.payload.provider}
            cid={c.contractId}
            key={c.contractId}
          />
        ))}
      </TableBody>
    </Table>
  );
};

const AppPaymentRequestRows: React.FC<{
  request: AppPaymentRequest;
  provider: string;
  cid: string;
}> = ({ request, provider, cid }) => {
  const { acceptAppPaymentRequests } = useWalletClient();
  const [searchParams] = useSearchParams();
  const onAccept = async () => {
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
          <Button className="accept-button" type="submit" onClick={() => onAccept()}>
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
                    <TableCell className="app-request-payment-quantity" align="right">
                      <PaymentQuantityDisplay quantity={quantity} />
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

export default AppPaymentRequestsTable;
