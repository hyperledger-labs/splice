// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useScanClient } from '@lfdecentralizedtrust/splice-common-frontend/scan-api';
import { useQuery, UseQueryResult } from '@tanstack/react-query';

export const useFeatureSupport = (): UseQueryResult<{
  delegatelessAutomation: boolean;
}> => {
  const scanClient = useScanClient();
  return useQuery({
    queryKey: ['featureSupport'],
    queryFn: async () => {
      const result = await scanClient.featureSupport();
      return {
        delegatelessAutomation: result.delegateless_automation,
      };
    },
  });
};

export default useFeatureSupport;
