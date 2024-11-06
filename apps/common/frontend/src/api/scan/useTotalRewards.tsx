// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { PollingStrategy } from 'common-frontend-utils';
import { GetRewardsCollectedResponse } from 'scan-openapi';

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
