import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { Contract, PollingStrategy } from 'common-frontend';

import { AppPaymentRequest } from '@daml.js/wallet-payments/lib/CN/Wallet/Payment';

import { useWalletClient } from '../contexts/WalletServiceContext';

export const useAppPaymentRequest = (cid: string): UseQueryResult<Contract<AppPaymentRequest>> => {
  const { getAppPaymentRequest } = useWalletClient();
  return useQuery({
    refetchInterval: PollingStrategy.NONE,
    queryKey: ['appPaymentRequest', cid],
    queryFn: async () => {
      return await getAppPaymentRequest(cid);
    },
    retry: 30,
    retryDelay: 500,
  });
};
