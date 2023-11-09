import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { PollingStrategy } from 'common-frontend';
import { ListDirectoryEntriesResponse } from 'directory-external-openapi';

import { useExternalDirectoryClient } from '../../context/ValidatorServiceContext';

const useEntriesWithPayData = (): UseQueryResult<ListDirectoryEntriesResponse> => {
  const refetchInterval = PollingStrategy.FIXED;

  const directoryApi = useExternalDirectoryClient();
  return useQuery({
    refetchInterval,
    queryKey: ['queryEntriesWithPayData'],
    queryFn: async () => {
      return directoryApi.listDirectoryEntries();
    },
  });
};

export default useEntriesWithPayData;
