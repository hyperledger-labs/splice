// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { PollingStrategy } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useInfiniteQuery, UseInfiniteQueryResult } from '@tanstack/react-query';
import { ListActivityRequest, ListActivityResponseItem } from 'scan-openapi';

import { useScanClient } from './ScanClientContext';

const useActivity = (): UseInfiniteQueryResult<ListActivityResponseItem[]> => {
  const scanClient = useScanClient();

  return useInfiniteQuery({
    queryKey: ['scan-api', 'listActivity'],
    queryFn: async ({ pageParam }) => {
      const requestBody = { begin_after_id: pageParam, page_size: 10 } as ListActivityRequest;
      const response = await scanClient.listActivity(requestBody);
      const activities = response.activities;
      // react-query requires us to return undefined here to show that no more data is available
      return activities.length === 0 ? undefined : activities;
    },
    getNextPageParam: lastPage => {
      return lastPage && lastPage[lastPage.length - 1].event_id;
    },
    keepPreviousData: true,
    refetchInterval: PollingStrategy.NONE,
  });
};

export default useActivity;
