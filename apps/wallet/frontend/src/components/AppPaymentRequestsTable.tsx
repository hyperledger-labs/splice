import { Contract, DirectoryEntry } from 'common-frontend';
import { Decimal } from 'decimal.js';
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
import { PaymentAmountDisplay, PaymentAmountTotalDisplay } from './AmountDisplay';

interface AppPaymentRequestsTableProps {
  requests: Contract<AppPaymentRequest>[];
  coinPrice: Decimal | undefined;
}

const AppPaymentRequestsTable: React.FC<AppPaymentRequestsTableProps> = ({
  requests,
  coinPrice,
}) => {
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
            coinPrice={coinPrice}
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
  coinPrice?: Decimal;
}> = ({ request, provider, cid, coinPrice }) => {
  const { acceptAppPaymentRequest, rejectAppPaymentRequest } = useWalletClient();
  const [searchParams] = useSearchParams();
  const onAccept = async () => {
    await acceptAppPaymentRequest(cid);
    const target = searchParams.get('redirect');
    if (target) {
      window.location.assign(target);
    }
  };
  const onReject = async () => {
    await rejectAppPaymentRequest(cid);
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
        <TableCell className="app-request-total-amount">
          <PaymentAmountTotalDisplay
            amounts={request.receiverAmounts.map(rq => rq.amount)}
            coinPrice={coinPrice}
          />
        </TableCell>
        <TableCell>
          <Button className="accept-button" type="submit" onClick={() => onAccept()}>
            Accept
          </Button>
          <Button className="reject-button" type="submit" onClick={() => onReject()}>
            Reject
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
                  <TableCell align="right">Amount</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {request.receiverAmounts.map(({ receiver, amount }, index) => (
                  <TableRow key={index} className="receiver-amount-row">
                    <TableCell className="app-request-receiver">
                      <DirectoryEntry partyId={receiver} />
                    </TableCell>
                    <TableCell className="app-request-payment-amount" align="right">
                      <PaymentAmountDisplay amount={amount} />
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
