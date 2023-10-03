import { useQuery, UseQueryResult } from '@tanstack/react-query';

import { PollingStrategy } from '../..';
import { useScanClient } from './ScanClientContext';

const useGetSvcPartyId = (): UseQueryResult<string> => {
  const scanClient = useScanClient();

  return useQuery({
    refetchInterval: PollingStrategy.NONE,
    queryKey: ['scan-api', 'getSvcPartyId'],
    queryFn: async () => {
      const response = await scanClient.getSvcPartyId();
      return response.svc_party_id;
    },
  });
};

export default useGetSvcPartyId;
