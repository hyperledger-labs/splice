import { UseQueryResult, useQuery } from '@tanstack/react-query';
import { Contract } from 'common-frontend';

import { DirectoryInstall } from '@daml.js/directory/lib/CN/Directory';

import { useProviderParty, usePrimaryParty } from '..';
import { useLedgerApiClient } from '../../contexts/LedgerApiContext';

const useDirectoryInstall = (): UseQueryResult<Contract<DirectoryInstall>> => {
  const operationName = 'queryDirectoryInstall';
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
        // react-query blows up if queryFn returns undefined; throw error to trigger retries
        throw new Error('Directory install contract not found');
      }
    },
    enabled: !!ledgerApi && !!primaryPartyId && !!providerPartyId, // wait for dependencies to be defined
  });
};

export default useDirectoryInstall;
