import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { GetTopValidatorsByValidatorRewardsResponse } from 'scan-openapi';

import { PollingStrategy } from '../..';
import { useScanClient } from './ScanClientContext';
import useGetRoundOfLatestData from './useGetRoundOfLatestData';

const useGetTopValidatorsByValidatorRewards =
  (): UseQueryResult<GetTopValidatorsByValidatorRewardsResponse> => {
    const scanClient = useScanClient();
    const latestRoundQuery = useGetRoundOfLatestData(PollingStrategy.FIXED);
    const latestRoundNumber = latestRoundQuery.data?.round;

    return useQuery({
      refetchInterval: PollingStrategy.FIXED,
      queryKey: ['scan-api', 'getTopValidatorsByValidatorRewards', latestRoundNumber],
      queryFn: async () => scanClient.getTopValidatorsByValidatorRewards(latestRoundNumber!, 10),
      enabled: latestRoundNumber !== undefined, // include round 0 as valid,
    });
  };

export default useGetTopValidatorsByValidatorRewards;
