import { UseQueryResult, useQuery } from '@tanstack/react-query';
import { Contract } from 'common-frontend';

import { DirectoryEntryContext } from '@daml.js/directory/lib/CN/Directory';

import { useLedgerApiClient } from '../contexts/LedgerApiContext';

const useDirectoryEntryContexts = (
  user?: string,
  provider?: string
): UseQueryResult<Contract<DirectoryEntryContext>[]> => {
  const operationName = 'querySubscriptions';
  const ledgerApi = useLedgerApiClient();

  return useQuery({
    queryKey: [operationName, ledgerApi, DirectoryEntryContext],
    queryFn: async () => {
      const response = await ledgerApi!.query(operationName, DirectoryEntryContext);
      return response
        .filter(s => s.payload.user === user && s.payload.provider === provider)
        .map(ledgerApi!.toContract);
    },
    enabled: !!ledgerApi && !!user && !!provider, // wait for dependencies to be defined
  });
};

export default useDirectoryEntryContexts;
