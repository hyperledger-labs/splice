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
    const fetchAppPayment = async () => {
      const { paymentRequestsList } = await listAppPaymentRequests();
      const req = paymentRequestsList.find(c => c.contractId === cid);
      if (!req) {
        throw new Error('Payment request contract not found');
      }
      setAppPayment(req);
    };
    fetchAppPayment();
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
