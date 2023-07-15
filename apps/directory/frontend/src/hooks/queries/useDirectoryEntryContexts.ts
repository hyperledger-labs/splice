import { UseQueryResult, useQuery } from '@tanstack/react-query';
import { Contract, useLedgerApiClient, usePrimaryParty } from 'common-frontend';

import { DirectoryEntryContext } from '@daml.js/directory/lib/CN/Directory';

import { useProviderParty } from '..';

const useDirectoryEntryContexts = (): UseQueryResult<Contract<DirectoryEntryContext>[]> => {
  const operationName = 'querySubscriptions';
  const ledgerApi = useLedgerApiClient();
  const { data: primaryPartyId } = usePrimaryParty();
  const { data: providerPartyId } = useProviderParty();

  return useQuery({
    queryKey: [operationName, ledgerApi, DirectoryEntryContext],
    queryFn: async () => {
      const response = await ledgerApi!.query(operationName, DirectoryEntryContext);
      return response
        .filter(s => s.payload.user === primaryPartyId && s.payload.provider === providerPartyId)
        .map(ledgerApi!.toContract);
    },
    enabled: !!ledgerApi && !!primaryPartyId && !!providerPartyId, // wait for dependencies to be defined
  });
};

export default useDirectoryEntryContexts;
