import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { PollingStrategy } from 'common-frontend';

import { useWalletClient } from '../contexts/WalletServiceContext';
import { WalletBalance } from '../models/models';

export const useBalance = (): UseQueryResult<WalletBalance> => {
  const walletClient = useWalletClient();
  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: ['balance'],
    queryFn: async () => {
      return await walletClient.getBalance();
    },
  });
};
