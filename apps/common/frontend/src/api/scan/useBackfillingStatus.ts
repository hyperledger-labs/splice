// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useQuery, UseQueryResult } from '@tanstack/react-query';

import { useScanClient } from './ScanClientContext';

const useGetBackfillingStatus = (): UseQueryResult<boolean> => {
  const scanClient = useScanClient();

  return useQuery({
    queryKey: ['scan-api', 'getBackfillingStatus'],
    queryFn: async () => {
      const response = await scanClient.getBackfillingStatus();
      return response.complete;
    },
  });
};

export default useGetBackfillingStatus;
