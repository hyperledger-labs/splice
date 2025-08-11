// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useQuery, UseQueryResult } from '@tanstack/react-query';

export const useFeatureSupport = (): UseQueryResult<{
  delegatelessAutomation: boolean;
}> => {
  return useQuery({
    queryKey: ['featureSupport'],
    queryFn: async () => {
      return {};
    },
  });
};

export default useFeatureSupport;
