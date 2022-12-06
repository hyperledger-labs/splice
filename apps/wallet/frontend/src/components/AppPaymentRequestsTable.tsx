import { useScanClient, Contract, DirectoryEntry } from 'common-frontend';
import { Decimal } from 'decimal.js';
import { Empty } from 'google-protobuf/google/protobuf/empty_pb';
import React, { useState, useEffect } from 'react';
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

import { OpenMiningRound } from '@daml.js/canton-coin/lib/CC/Round';
import { AppPaymentRequest } from '@daml.js/wallet-payments-0.1.0/lib/CN/Wallet/Payment';

import { useWalletClient } from '../contexts/WalletServiceContext';
import { PaymentQuantityDisplay, PaymentQuantityTotalDisplay } from './QuantityDisplay';

interface AppPaymentRequestsProps {
  requests: Contract<AppPaymentRequest>[];
}

function useCoinPrice(): Decimal | undefined {
  const [coinPrice, setCoinPrice] = useState<Decimal | undefined>();
  const scanClient = useScanClient();
  useEffect(() => {
    const fetchCoinPrice = async () => {
      const tctx = await scanClient.getTransferContext(new Empty(), undefined);
      const omr = tctx.getLatestOpenMiningRound();
      if (omr) {
        const p = Contract.decode(omr, OpenMiningRound);
        setCoinPrice(new Decimal(p.payload.coinPrice));
      }
    };
    fetchCoinPrice();
  }, [scanClient]);

  return coinPrice;
}

const AppPaymentRequestsTable: React.FC<AppPaymentRequestsProps> = ({ requests }) => {
  const coinPrice = useCoinPrice();

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
        <TableCell className="app-request-total-quantity">
          <PaymentQuantityTotalDisplay
            quantities={request.receiverQuantities.map(rq => rq.quantity)}
            coinPrice={coinPrice}
          />
        </TableCell>
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
                  <TableRow key={index} className="receiver-quantity-row">
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
