import { UseQueryResult, useQuery } from '@tanstack/react-query';
import { FetchDirectoryInstallResponse } from 'directory-external-openapi';

import { DirectoryInstall } from '@daml.js/directory/lib/CN/Directory';

import { useExternalDirectoryClient } from '../../context/ValidatorServiceContext';

export const QueryDirectoryInstallOperationName = 'queryDirectoryInstall';
const useDirectoryInstall = (): UseQueryResult<FetchDirectoryInstallResponse | null> => {
  const operationName = QueryDirectoryInstallOperationName;

  const directoryApi = useExternalDirectoryClient();
  return useQuery({
    queryKey: [operationName, DirectoryInstall],
    queryFn: async () => {
      try {
        return await directoryApi.fetchDirectoryInstall();
      } catch (error) {
        console.debug(error);
        return null;
      }
    },
    refetchInterval: (data, query) => {
      if (data !== null && data !== undefined) {
        // Install contracts are not not expected to change. Once a contract is found, stop refetching.
        console.debug('useDirectoryInstall: contract found, stopping');
        return false;
      } else if (query.state.status === 'error') {
        console.debug('useDirectoryInstall: query error, stopping');
        return false;
      } else {
        console.debug('useDirectoryInstall: no data, refetching');
        return 500;
      }
    },
    enabled: !!directoryApi,
  });
};

export default useDirectoryInstall;
