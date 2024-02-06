import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { ContractWithState, PollingStrategy } from 'common-frontend-utils';

import { AppPaymentRequest } from '@daml.js/wallet-payments/lib/CN/Wallet/Payment';

import { useWalletClient } from '../contexts/WalletServiceContext';

export const useAppPaymentRequest = (
  cid: string
): UseQueryResult<ContractWithState<AppPaymentRequest>> => {
  const { getAppPaymentRequest } = useWalletClient();
  return useQuery({
    refetchInterval: PollingStrategy.FIXED, // it might change domains, so we want to periodically update it
    queryKey: ['appPaymentRequest', cid],
    queryFn: async () => {
      return await getAppPaymentRequest(cid);
    },
    retry: 30,
    retryDelay: 500,
  });
};
