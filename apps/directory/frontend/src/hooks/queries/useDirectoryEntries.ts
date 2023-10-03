import { UseQueryResult, useQuery } from '@tanstack/react-query';
import { Contract, useLedgerApiClient, usePrimaryParty } from 'common-frontend';

import { DirectoryEntry } from '@daml.js/directory/lib/CN/Directory';

import { useProviderParty } from '..';

const useDirectoryEntries = (
  refetchInterval: false | number
): UseQueryResult<Contract<DirectoryEntry>[]> => {
  const operationName = 'queryDirectoryEntries';
  const ledgerApi = useLedgerApiClient();
  const { data: primaryPartyId } = usePrimaryParty();
  const { data: providerPartyId } = useProviderParty();

  return useQuery({
    refetchInterval,
    queryKey: [operationName, ledgerApi, DirectoryEntry],
    queryFn: async () => {
      const directoryEntries = await ledgerApi!.query(operationName, DirectoryEntry);
      return directoryEntries
        .filter(c => c.payload.user === primaryPartyId && c.payload.provider === providerPartyId)
        .map(ev => ledgerApi!.toContract(ev));
    },
    enabled: !!ledgerApi && !!primaryPartyId && !!providerPartyId, // wait for dependencies to be defined
  });
};

export default useDirectoryEntries;
