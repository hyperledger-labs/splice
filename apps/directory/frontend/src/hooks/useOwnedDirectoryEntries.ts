import { UseQueryResult, useQuery } from '@tanstack/react-query';
import { Contract } from 'common-frontend';

import { DirectoryEntry } from '@daml.js/directory/lib/CN/Directory';

import { useLedgerApiClient } from '../contexts/LedgerApiContext';

const useOwnedDirectoryEntries: (
  user?: string,
  provider?: string
) => UseQueryResult<Contract<DirectoryEntry>[]> = (user, provider) => {
  const operationName = 'queryDirectoryEntries';
  const ledgerApi = useLedgerApiClient();

  return useQuery({
    queryKey: [operationName, ledgerApi, DirectoryEntry],
    queryFn: async () => {
      const directoryEntries = await ledgerApi!.query(operationName, DirectoryEntry);
      return directoryEntries
        .filter(c => c.payload.user === user && c.payload.provider === provider)
        .map(ev => ledgerApi!.toContract(ev));
    },
    enabled: !!ledgerApi && !!user && !!provider, // wait for dependencies to be defined
  });
};

export default useOwnedDirectoryEntries;
