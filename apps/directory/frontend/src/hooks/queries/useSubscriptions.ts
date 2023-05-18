import { UseQueryResult, useQuery } from '@tanstack/react-query';
import { Contract } from 'common-frontend';

import { Subscription } from '@daml.js/wallet-payments-0.1.0/lib/CN/Wallet/Subscriptions';

import { useProviderParty, usePrimaryParty } from '..';
import { useLedgerApiClient } from '../../contexts/LedgerApiContext';

const useSubscriptions = (): UseQueryResult<Contract<Subscription>[]> => {
  const operationName = 'querySubscriptions';
  const ledgerApi = useLedgerApiClient();
  const { data: primaryPartyId } = usePrimaryParty();
  const { data: providerPartyId } = useProviderParty();

  return useQuery({
    queryKey: [operationName, ledgerApi, Subscription],
    queryFn: async () => {
      const response = await ledgerApi!.query(operationName, Subscription);
      return response
        .filter(s => s.payload.sender === primaryPartyId && s.payload.provider === providerPartyId)
        .map(ledgerApi!.toContract);
    },
    enabled: !!ledgerApi && !!primaryPartyId && !!providerPartyId, // wait for dependencies to be defined
  });
};

export default useSubscriptions;
