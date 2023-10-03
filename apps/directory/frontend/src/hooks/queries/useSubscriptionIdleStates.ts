import { UseQueryResult, useQuery } from '@tanstack/react-query';
import { Contract, useLedgerApiClient, usePrimaryParty } from 'common-frontend';

import { SubscriptionIdleState } from '@daml.js/wallet-payments-0.1.0/lib/CN/Wallet/Subscriptions';

import { useProviderParty } from '..';

const useSubscriptionIdleStates = (
  refetchInterval: false | number
): UseQueryResult<Contract<SubscriptionIdleState>[]> => {
  const operationName = 'querySubscriptionIdleStates';
  const ledgerApi = useLedgerApiClient();
  const { data: primaryPartyId } = usePrimaryParty();
  const { data: providerPartyId } = useProviderParty();

  return useQuery({
    refetchInterval,
    queryKey: [operationName, ledgerApi, SubscriptionIdleState],
    queryFn: async () => {
      const response = await ledgerApi!.query(operationName, SubscriptionIdleState);
      return response
        .filter(
          s =>
            s.payload.subscriptionData.sender === primaryPartyId &&
            s.payload.subscriptionData.provider === providerPartyId
        )
        .map(ledgerApi!.toContract);
    },
    enabled: !!ledgerApi && !!primaryPartyId && !!providerPartyId, // wait for dependencies to be defined
  });
};

export default useSubscriptionIdleStates;
