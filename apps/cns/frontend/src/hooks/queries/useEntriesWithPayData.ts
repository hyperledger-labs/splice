import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { ListCnsEntriesResponse } from 'cns-external-openapi';
import { PollingStrategy } from 'common-frontend';

import { useExternalCnsClient } from '../../context/ValidatorServiceContext';

const useEntriesWithPayData = (): UseQueryResult<ListCnsEntriesResponse> => {
  const refetchInterval = PollingStrategy.FIXED;

  const cnsApi = useExternalCnsClient();
  return useQuery({
    refetchInterval,
    queryKey: ['queryEntriesWithPayData'],
    queryFn: async () => {
      return cnsApi.listCnsEntries();
    },
  });
};

export default useEntriesWithPayData;
