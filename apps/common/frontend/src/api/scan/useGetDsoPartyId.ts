// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { PollingStrategy } from 'common-frontend-utils';

import { useScanClient } from './ScanClientContext';

const useGetDsoPartyId = (): UseQueryResult<string> => {
  const scanClient = useScanClient();

  return useQuery({
    refetchInterval: PollingStrategy.NONE,
    queryKey: ['scan-api', 'getDsoPartyId'],
    queryFn: async () => {
      const response = await scanClient.getDsoPartyId();
      return response.dso_party_id;
    },
  });
};

export default useGetDsoPartyId;
