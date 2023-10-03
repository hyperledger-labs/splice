import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { GetRewardsCollectedResponse } from 'scan-openapi';

import { PollingStrategy } from '../..';
import { useScanClient } from './ScanClientContext';

const useTotalRewards = (): UseQueryResult<GetRewardsCollectedResponse> => {
  const scanClient = useScanClient();
  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: ['scan-api', 'getTotalRewards'],
    queryFn: async () => scanClient.getRewardsCollected(),
  });
};

export default useTotalRewards;
