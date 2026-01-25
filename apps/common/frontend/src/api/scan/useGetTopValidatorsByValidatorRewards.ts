// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { GetTopValidatorsByValidatorRewardsResponse } from '@lfdecentralizedtrust/scan-openapi';

import { useScanClient } from './ScanClientContext';
import useGetRoundOfLatestData from './useGetRoundOfLatestData';

const useGetTopValidatorsByValidatorRewards =
  (): UseQueryResult<GetTopValidatorsByValidatorRewardsResponse> => {
    const scanClient = useScanClient();
    const latestRoundQuery = useGetRoundOfLatestData();
    const latestRoundNumber = latestRoundQuery.data?.round;

    return useQuery({
      queryKey: ['scan-api', 'getTopValidatorsByValidatorRewards', latestRoundNumber],
      queryFn: async () => scanClient.getTopValidatorsByValidatorRewards(latestRoundNumber!, 10),
      enabled: latestRoundNumber !== undefined, // include round 0 as valid,
    });
  };

export default useGetTopValidatorsByValidatorRewards;
