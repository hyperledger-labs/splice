import { UseQueryResult, useQuery } from '@tanstack/react-query';
import { Contract } from 'common-frontend';

import { Subscription } from '@daml.js/wallet-payments-0.1.0/lib/CN/Wallet/Subscriptions';

import { useLedgerApiClient } from '../contexts/LedgerApiContext';

const useSubscriptions = (
  user?: string,
  provider?: string
): UseQueryResult<Contract<Subscription>[]> => {
  const operationName = 'querySubscriptions';
  const ledgerApi = useLedgerApiClient();

  return useQuery({
    queryKey: [operationName, ledgerApi, Subscription],
    queryFn: async () => {
      const response = await ledgerApi!.query(operationName, Subscription);
      return response
        .filter(s => s.payload.sender === user && s.payload.provider === provider)
        .map(ledgerApi!.toContract);
    },
    enabled: !!ledgerApi && !!user && !!provider, // wait for dependencies to be defined
  });
};

export default useSubscriptions;
