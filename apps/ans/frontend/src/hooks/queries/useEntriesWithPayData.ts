// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { ListAnsEntriesResponse } from 'ans-external-openapi';
import { PollingStrategy } from 'common-frontend-utils';

import { useExternalAnsClient } from '../../context/AnsServiceContext';

const useEntriesWithPayData = (): UseQueryResult<ListAnsEntriesResponse> => {
  const refetchInterval = PollingStrategy.FIXED;

  const ansApi = useExternalAnsClient();
  return useQuery({
    refetchInterval,
    queryKey: ['queryEntriesWithPayData'],
    queryFn: async () => {
      return ansApi.listAnsEntries();
    },
  });
};

export default useEntriesWithPayData;
