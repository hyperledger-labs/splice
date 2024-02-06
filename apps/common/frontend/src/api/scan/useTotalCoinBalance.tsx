import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { PollingStrategy } from 'common-frontend-utils';
import { GetTotalCoinBalanceResponse } from 'scan-openapi';

import { useScanClient } from './ScanClientContext';
import useGetRoundOfLatestData from './useGetRoundOfLatestData';

const useTotalCoinBalance = (): UseQueryResult<GetTotalCoinBalanceResponse> => {
  const scanClient = useScanClient();
  const latestRoundQuery = useGetRoundOfLatestData(PollingStrategy.FIXED);
  const latestRoundNumber = latestRoundQuery.data?.round;

  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: ['scan-api', 'getTotalCoinBalance', latestRoundNumber],
    queryFn: async () => scanClient.getTotalCoinBalance(latestRoundNumber!),
    enabled: latestRoundNumber !== undefined, // include round 0 as valid,
  });
};

export default useTotalCoinBalance;
