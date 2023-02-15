import { Contract } from 'common-frontend';
import { Decimal } from 'decimal.js';
import React, { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';

import { Stack, Typography } from '@mui/material';

import { AppPaymentRequest } from '@daml.js/wallet-payments-0.1.0/lib/CN/Wallet/Payment';

import AppPaymentRequestsTable from '../components/AppPaymentRequestsTable';
import { useWalletClient } from '../contexts/WalletServiceContext';

interface ConfirmPaymentProps {
  coinPrice: Decimal | undefined;
}

const ConfirmPayment: React.FC<ConfirmPaymentProps> = ({ coinPrice }) => {
  const { listAppPaymentRequests } = useWalletClient();
  const { cid } = useParams();
  const [appPayment, setAppPayment] = useState<Contract<AppPaymentRequest>>();
  useEffect(() => {
    let timer: NodeJS.Timeout | undefined;
    const fetchAppPayment = async (n: number) => {
      const { paymentRequestsList } = await listAppPaymentRequests();
      const req = paymentRequestsList.find(c => c.contractId === cid);
      if (req) {
        console.debug('Payment request found');
        setAppPayment(req);
      } else if (n < 0) {
        throw new Error('Payment request not found, retries exceeded giving up...');
      } else {
        console.debug('Payment request not found, trying again...');
        timer = setTimeout(fetchAppPayment, 500, n - 1);
      }
    };
    fetchAppPayment(30);
    return () => {
      if (timer !== undefined) clearTimeout(timer);
    };
  }, [cid, listAppPaymentRequests]);

  if (appPayment === undefined || cid === undefined) {
    return <div>Loading...</div>;
  }
  return (
    <Stack>
      <Typography variant="h6">Please accept the following payment request:</Typography>
      <AppPaymentRequestsTable requests={[appPayment]} coinPrice={coinPrice} />
    </Stack>
  );
};

export default ConfirmPayment;
