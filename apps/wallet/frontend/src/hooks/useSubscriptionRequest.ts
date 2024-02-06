import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { Contract, PollingStrategy } from 'common-frontend-utils';

import { SubscriptionRequest } from '@daml.js/wallet-payments/lib/CN/Wallet/Subscriptions';

import { useWalletClient } from '../contexts/WalletServiceContext';

export const useSubscriptionRequest = (
  cid: string
): UseQueryResult<Contract<SubscriptionRequest>> => {
  const { getSubscriptionRequest } = useWalletClient();

  return useQuery({
    refetchInterval: PollingStrategy.NONE,
    queryKey: ['subscriptionRequest', cid],
    queryFn: async () => {
      return await getSubscriptionRequest(cid);
    },
  });
};
