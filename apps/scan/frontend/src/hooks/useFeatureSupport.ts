// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { useScanClient } from '@lfdecentralizedtrust/splice-common-frontend/scan-api';

// eslint-disable-next-line @typescript-eslint/no-empty-object-type
export const useFeatureSupport = (): UseQueryResult<{
}> => {
  const scanClient = useScanClient();
  return useQuery({
    queryKey: ['featureSupport'],
    queryFn: async () => {
      await scanClient.featureSupport();
      return {
      };
    },
  });
};

export default useFeatureSupport;
