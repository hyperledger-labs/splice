import { UseQueryResult, useQuery } from '@tanstack/react-query';
import { Contract, useLedgerApiClient, usePrimaryParty } from 'common-frontend';

import { Subscription } from '@daml.js/wallet-payments/lib/CN/Wallet/Subscriptions';

import { useProviderParty } from '..';

const useSubscriptions = (
  refetchInterval: false | number
): UseQueryResult<Contract<Subscription>[]> => {
  const operationName = 'querySubscriptions';
  const ledgerApi = useLedgerApiClient();
  const { data: primaryPartyId } = usePrimaryParty();
  const { data: providerPartyId } = useProviderParty();

  return useQuery({
    refetchInterval,
    queryKey: [operationName, ledgerApi, Subscription],
    queryFn: async () => {
      const response = await ledgerApi!.query(operationName, Subscription);
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

export default useSubscriptions;
