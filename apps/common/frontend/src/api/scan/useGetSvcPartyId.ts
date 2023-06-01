import { useQuery, UseQueryResult } from '@tanstack/react-query';

import { useScanClient } from './ScanClientContext';

const useGetSvcPartyId = (): UseQueryResult<string> => {
  const scanClient = useScanClient();

  return useQuery({
    queryKey: ['scan-api', 'getSvcPartyId'],
    queryFn: async () => {
      const response = await scanClient.getSvcPartyId();
      return response.svcPartyId;
    },
  });
};

export default useGetSvcPartyId;
