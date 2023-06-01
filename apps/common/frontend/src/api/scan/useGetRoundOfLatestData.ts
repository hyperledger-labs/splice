import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { GetRoundOfLatestDataResponse } from 'scan-openapi';

import { useScanClient } from './ScanClientContext';

const useGetRoundOfLatestData = (): UseQueryResult<GetRoundOfLatestDataResponse> => {
  const scanClient = useScanClient();

  return useQuery({
    queryKey: ['scan-api', 'getRoundOfLatestData'],
    queryFn: async () => scanClient.getRoundOfLatestData(),
  });
};

export default useGetRoundOfLatestData;
