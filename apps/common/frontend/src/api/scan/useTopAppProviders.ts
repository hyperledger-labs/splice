// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { UseQueryResult, useQuery } from '@tanstack/react-query';
import { GetTopProvidersByAppRewardsResponse } from '@lfdecentralizedtrust/scan-openapi';

import { useScanClient } from './ScanClientContext';
import useGetRoundOfLatestData from './useGetRoundOfLatestData';

const useTopAppProviders = (): UseQueryResult<GetTopProvidersByAppRewardsResponse> => {
  const scanClient = useScanClient();
  const latestRoundQuery = useGetRoundOfLatestData();
  const latestRoundNumber = latestRoundQuery.data?.round;

  return useQuery({
    queryKey: ['scan-api', 'getTopProvidersByAppRewards', latestRoundNumber],
    queryFn: async () => scanClient.getTopProvidersByAppRewards(latestRoundNumber!, 10),
    enabled: latestRoundNumber !== undefined, // include round 0 as valid
  });
};

export default useTopAppProviders;
