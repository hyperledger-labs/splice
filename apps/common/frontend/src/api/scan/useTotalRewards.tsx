// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { GetRewardsCollectedResponse } from '@lfdecentralizedtrust/scan-openapi';

import { useScanClient } from './ScanClientContext';

const useTotalRewards = (): UseQueryResult<GetRewardsCollectedResponse> => {
  const scanClient = useScanClient();
  return useQuery({
    queryKey: ['scan-api', 'getTotalRewards'],
    queryFn: async () => scanClient.getRewardsCollected(),
  });
};

export default useTotalRewards;
