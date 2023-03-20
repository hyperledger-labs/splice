import { sameContracts, useInterval, Contract } from 'common-frontend';
import { Decimal } from 'decimal.js';
import React, { useCallback, useState } from 'react';
import { useParams } from 'react-router-dom';

import { Stack } from '@mui/material';

import { AppPaymentRequest } from '@daml.js/wallet-payments-0.1.0/lib/CN/Wallet/Payment';

import AppPaymentRequestsTable from '../components/AppPaymentRequestsTable';
import { useWalletClient } from '../contexts/WalletServiceContext';

interface AppPaymentRequestsProps {
  coinPrice: Decimal | undefined;
}

const AppPaymentRequests: React.FC<AppPaymentRequestsProps> = ({ coinPrice }) => {
  const { listAppPaymentRequests } = useWalletClient();
  const { cid } = useParams();

  const [appPaymentRequests, setAppPaymentRequests] = useState<Contract<AppPaymentRequest>[]>([]);
  const fetchAppPaymentRequests = useCallback(async () => {
    const { paymentRequestsList } = await listAppPaymentRequests();
    const filteredReqs = () => {
      if (!cid) return paymentRequestsList;
      else return paymentRequestsList.filter(c => c.contractId === cid);
    };
    setAppPaymentRequests(prev => (sameContracts(filteredReqs(), prev) ? prev : filteredReqs()));
  }, [listAppPaymentRequests, setAppPaymentRequests, cid]);
  useInterval(fetchAppPaymentRequests);

  return (
    <Stack spacing={2}>
      <AppPaymentRequestsTable requests={appPaymentRequests} coinPrice={coinPrice} />
    </Stack>
  );
};

export default AppPaymentRequests;
