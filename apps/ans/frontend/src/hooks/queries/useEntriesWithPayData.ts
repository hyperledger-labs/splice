import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { ListAnsEntriesResponse } from 'ans-external-openapi';
import { PollingStrategy } from 'common-frontend-utils';

import { useExternalAnsClient } from '../../context/ValidatorServiceContext';

const useEntriesWithPayData = (): UseQueryResult<ListAnsEntriesResponse> => {
  const refetchInterval = PollingStrategy.FIXED;

  const ansApi = useExternalAnsClient();
  return useQuery({
    refetchInterval,
    queryKey: ['queryEntriesWithPayData'],
    queryFn: async () => {
      return ansApi.listAnsEntries();
    },
  });
};

export default useEntriesWithPayData;
