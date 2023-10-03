import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { PollingStrategy } from 'common-frontend';

import { useWalletClient } from '../contexts/WalletServiceContext';
import { AppPaymentRequest } from '../models/models';

export const useAppPaymentRequest = (cid: string): UseQueryResult<AppPaymentRequest> => {
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
