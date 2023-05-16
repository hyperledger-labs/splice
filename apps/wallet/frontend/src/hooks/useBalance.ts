import { useQuery, UseQueryResult } from '@tanstack/react-query';

import { useWalletClient } from '../contexts/WalletServiceContext';
import { WalletBalance } from '../models/models';

export const useBalance = (): UseQueryResult<WalletBalance> => {
  const walletClient = useWalletClient();
  return useQuery({
    queryKey: ['balance'],
    queryFn: async () => {
      return await walletClient.getBalance();
    },
  });
};
