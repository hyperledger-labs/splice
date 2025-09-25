// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { useScanClient } from '@lfdecentralizedtrust/splice-common-frontend/scan-api';

export const useFeatureSupport = (): UseQueryResult<{
  noHoldingFeesOnTransfers: boolean;
}> => {
  const scanClient = useScanClient();
  return useQuery({
    queryKey: ['featureSupport'],
    queryFn: async () => {
      const result = await scanClient.featureSupport();
      return {
        noHoldingFeesOnTransfers: result.no_holding_fees_on_transfers,
      };
    },
  });
};

export default useFeatureSupport;
