// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { GetRoundOfLatestDataResponse } from '@lfdecentralizedtrust/scan-openapi';

import { useScanClient } from './ScanClientContext';

const useGetRoundOfLatestData = (): UseQueryResult<GetRoundOfLatestDataResponse> => {
  const scanClient = useScanClient();

  return useQuery({
    queryKey: ['scan-api', 'getRoundOfLatestData'],
    queryFn: async () => scanClient.getRoundOfLatestData(),
  });
};

export default useGetRoundOfLatestData;
