import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { GetTopValidatorsByPurchasedTrafficResponse } from 'scan-openapi';

import { useScanClient } from './ScanClientContext';
import useGetRoundOfLatestData from './useGetRoundOfLatestData';

const useGetTopValidatorsByPurchasedTraffic =
  (): UseQueryResult<GetTopValidatorsByPurchasedTrafficResponse> => {
    const scanClient = useScanClient();
    const latestRoundQuery = useGetRoundOfLatestData();
    const latestRoundNumber = latestRoundQuery.data?.round;

    return useQuery({
      queryKey: ['scan-api', 'getTopValidatorsByPurchasedTraffic', latestRoundNumber],
      queryFn: async () => scanClient.getTopValidatorsByPurchasedTraffic(latestRoundNumber!, 10),
      enabled: latestRoundNumber !== undefined, // include round 0 as valid,
    });
  };

export default useGetTopValidatorsByPurchasedTraffic;
