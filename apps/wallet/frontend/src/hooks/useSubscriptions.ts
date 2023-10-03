import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { PollingStrategy } from 'common-frontend';

import { useWalletClient } from '../contexts/WalletServiceContext';
import { WalletSubscription } from '../models/models';

export const useSubscriptions = (): UseQueryResult<WalletSubscription[]> => {
  const { listSubscriptions } = useWalletClient();

  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: ['subscriptions'],
    queryFn: async () => {
      return (await listSubscriptions()).subscriptionsList;
    },
  });
};
