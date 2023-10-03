import { UseQueryResult, useQuery } from '@tanstack/react-query';
import { GetTopProvidersByAppRewardsResponse } from 'scan-openapi';

import { PollingStrategy } from '../..';
import { useScanClient } from './ScanClientContext';
import useGetRoundOfLatestData from './useGetRoundOfLatestData';

const useTopAppProviders = (): UseQueryResult<GetTopProvidersByAppRewardsResponse> => {
  const scanClient = useScanClient();
  const latestRoundQuery = useGetRoundOfLatestData(PollingStrategy.FIXED);
  const latestRoundNumber = latestRoundQuery.data?.round;

  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: ['scan-api', 'getTopProvidersByAppRewards', latestRoundNumber],
    queryFn: async () => scanClient.getTopProvidersByAppRewards(latestRoundNumber!, 10),
    enabled: latestRoundNumber !== undefined, // include round 0 as valid
  });
};

export default useTopAppProviders;
