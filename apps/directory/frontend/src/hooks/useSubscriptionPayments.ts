import { UseQueryResult, useQuery } from '@tanstack/react-query';
import { Contract } from 'common-frontend';

import { SubscriptionPayment } from '@daml.js/wallet-payments-0.1.0/lib/CN/Wallet/Subscriptions';

import { useLedgerApiClient } from '../contexts/LedgerApiContext';

const useSubscriptionPayments = (
  user?: string,
  provider?: string
): UseQueryResult<Contract<SubscriptionPayment>[]> => {
  const operationName = 'querySubscriptionPayments';
  const ledgerApi = useLedgerApiClient();

  return useQuery({
    queryKey: [operationName, ledgerApi, SubscriptionPayment],
    queryFn: async () => {
      const response = await ledgerApi!.query(operationName, SubscriptionPayment);
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

export default useSubscriptionPayments;
