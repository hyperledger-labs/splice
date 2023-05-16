import { UseQueryResult, useQuery } from '@tanstack/react-query';
import { Contract } from 'common-frontend';

import { SubscriptionIdleState } from '@daml.js/wallet-payments-0.1.0/lib/CN/Wallet/Subscriptions';

import { useLedgerApiClient } from '../contexts/LedgerApiContext';

const useSubscriptionIdleStates = (
  user?: string,
  provider?: string
): UseQueryResult<Contract<SubscriptionIdleState>[]> => {
  const operationName = 'querySubscriptionIdleStates';
  const ledgerApi = useLedgerApiClient();

  return useQuery({
    queryKey: [operationName, ledgerApi, SubscriptionIdleState],
    queryFn: async () => {
      const response = await ledgerApi!.query(operationName, SubscriptionIdleState);
      return response
        .filter(
          s =>
            s.payload.subscriptionData.sender === user &&
            s.payload.subscriptionData.provider === provider
        )
        .map(ledgerApi!.toContract);
    },
    enabled: !!ledgerApi && !!user && !!provider, // wait for dependencies to be defined
  });
};

export default useSubscriptionIdleStates;
