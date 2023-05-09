import { useQuery, UseQueryResult } from '@tanstack/react-query';

import { useWalletClient } from '../contexts/WalletServiceContext';
import { SubscriptionRequestWithContext } from '../models/models';

export const useSubscriptionRequest: (
  cid: string
) => UseQueryResult<SubscriptionRequestWithContext> = (cid: string) => {
  const { getSubscriptionRequest } = useWalletClient();

  return useQuery({
    queryKey: ['subscriptionRequest', cid],
    queryFn: async () => {
      return await getSubscriptionRequest(cid);
    },
  });
};
