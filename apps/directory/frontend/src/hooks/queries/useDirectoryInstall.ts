import { UseQueryResult, useQuery } from '@tanstack/react-query';
import { Contract } from 'common-frontend';

import { DirectoryInstall } from '@daml.js/directory/lib/CN/Directory';

import { useProviderParty, usePrimaryParty } from '..';
import { useLedgerApiClient } from '../../contexts/LedgerApiContext';

export const QueryDirectoryInstallOperationName = 'queryDirectoryInstall';
const useDirectoryInstall = (): UseQueryResult<Contract<DirectoryInstall> | null> => {
  const operationName = QueryDirectoryInstallOperationName;
  const ledgerApi = useLedgerApiClient();
  const { data: primaryPartyId } = usePrimaryParty();
  const { data: providerPartyId } = useProviderParty();

  return useQuery({
    queryKey: [operationName, ledgerApi, DirectoryInstall],
    queryFn: async () => {
      const result = await ledgerApi!.query(operationName, DirectoryInstall);
      const directoryInstall = result
        .map(ev => ledgerApi!.toContract(ev))
        .find(c => c.payload.user === primaryPartyId && c.payload.provider === providerPartyId);

      if (directoryInstall) {
        return directoryInstall;
      } else {
        // react-query blows up if queryFn returns undefined
        return null;
      }
    },
    refetchInterval: (data, query) => {
      if (data !== null && data !== undefined) {
        // Install contracts are not not expected to change. Once a contract is found, stop refetching.
        console.debug('useDirectoryInstall: contract found, stopping');
        return false;
      } else if (query.state.status === 'error') {
        //
        console.debug('useDirectoryInstall: query error, stopping');
        return false;
      } else {
        console.debug('useDirectoryInstall: no data, refetching');
        return 500;
      }
    },
    enabled: !!ledgerApi && !!primaryPartyId && !!providerPartyId, // wait for dependencies to be defined
  });
};

export default useDirectoryInstall;
