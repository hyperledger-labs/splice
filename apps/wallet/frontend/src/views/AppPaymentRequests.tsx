import { sameContracts, useInterval, Contract } from 'common-frontend';
import React, { useCallback, useState } from 'react';
import { useParams } from 'react-router-dom';

import { Stack } from '@mui/material';

import { AppPaymentRequest } from '@daml.js/wallet-payments-0.1.0/lib/CN/Wallet/Payment';

import AppPaymentRequestsTable from '../components/AppPaymentRequestsTable';
import { useWalletClient } from '../contexts/WalletServiceContext';

const AppPaymentRequests: React.FC = () => {
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
  useInterval(fetchAppPaymentRequests, 500);

  return (
    <Stack spacing={2}>
      <AppPaymentRequestsTable requests={appPaymentRequests} />
    </Stack>
  );
};

export default AppPaymentRequests;
