import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { GetRewardsCollectedResponse } from 'scan-openapi';

import { useScanClient } from './ScanClientContext';

const useTotalRewards = (): UseQueryResult<GetRewardsCollectedResponse> => {
  const scanClient = useScanClient();
  return useQuery({
    queryKey: ['scan-api', 'getTotalRewards'],
    queryFn: async () => scanClient.getRewardsCollected(),
  });
};

export default useTotalRewards;
