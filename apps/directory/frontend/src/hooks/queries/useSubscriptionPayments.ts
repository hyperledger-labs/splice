import { UseQueryResult, useQuery } from '@tanstack/react-query';
import { Contract, useLedgerApiClient, usePrimaryParty } from 'common-frontend';

import { SubscriptionPayment } from '@daml.js/wallet-payments-0.1.0/lib/CN/Wallet/Subscriptions';

import { useProviderParty } from '..';

const useSubscriptionPayments = (
  refetchInterval: false | number
): UseQueryResult<Contract<SubscriptionPayment>[]> => {
  const operationName = 'querySubscriptionPayments';
  const ledgerApi = useLedgerApiClient();
  const { data: primaryPartyId } = usePrimaryParty();
  const { data: providerPartyId } = useProviderParty();

  return useQuery({
    refetchInterval,
    queryKey: [operationName, ledgerApi, SubscriptionPayment],
    queryFn: async () => {
      const response = await ledgerApi!.query(operationName, SubscriptionPayment);
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

export default useSubscriptionPayments;
