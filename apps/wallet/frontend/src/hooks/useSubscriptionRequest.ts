import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { PollingStrategy } from 'common-frontend';

import { useWalletClient } from '../contexts/WalletServiceContext';
import { SubscriptionRequestWithContext } from '../models/models';

export const useSubscriptionRequest = (
  cid: string
): UseQueryResult<SubscriptionRequestWithContext> => {
  const { getSubscriptionRequest } = useWalletClient();

  return useQuery({
    refetchInterval: PollingStrategy.NONE,
    queryKey: ['subscriptionRequest', cid],
    queryFn: async () => {
      return await getSubscriptionRequest(cid);
    },
  });
};
