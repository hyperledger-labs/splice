import { UseQueryResult, useQuery } from '@tanstack/react-query';
import { Contract } from 'common-frontend';

import { DirectoryInstall } from '@daml.js/directory/lib/CN/Directory';

import { useLedgerApiClient } from '../contexts/LedgerApiContext';

const useDirectoryInstall = (
  user?: string,
  provider?: string
): UseQueryResult<Contract<DirectoryInstall>> => {
  const operationName = 'queryDirectoryInstall';
  const ledgerApi = useLedgerApiClient();

  return useQuery({
    queryKey: [operationName, ledgerApi, DirectoryInstall],
    queryFn: async () => {
      const result = await ledgerApi!.query(operationName, DirectoryInstall);
      const directoryInstall = result
        .map(ev => ledgerApi!.toContract(ev))
        .find(c => c.payload.user === user && c.payload.provider === provider);

      if (directoryInstall) {
        return directoryInstall;
      } else {
        // react-query blows up if queryFn returns undefined; throw error to trigger retries
        throw new Error('Directory install contract not found');
      }
    },
    enabled: !!ledgerApi && !!user && !!provider, // wait for dependencies to be defined
  });
};

export default useDirectoryInstall;
