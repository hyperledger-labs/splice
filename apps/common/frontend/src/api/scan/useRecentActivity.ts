import { useInfiniteQuery, UseInfiniteQueryResult } from '@tanstack/react-query';
import { ListRecentActivityRequest, ListRecentActivityResponseItem } from 'scan-openapi';

import { useScanClient } from './ScanClientContext';

const useRecentActivity = (): UseInfiniteQueryResult<ListRecentActivityResponseItem[]> => {
  const scanClient = useScanClient();

  return useInfiniteQuery({
    queryKey: ['scan-api', 'listRecentActivity'],
    queryFn: async ({ pageParam }) => {
      const requestBody = { beginAfterId: pageParam, pageSize: 10 } as ListRecentActivityRequest;
      const response = await scanClient.listRecentActivity(requestBody);
      const activities = response.activities;
      // react-query requires us to return undefined here to show that no more data is available
      return activities.length === 0 ? undefined : activities;
    },
    getNextPageParam: lastPage => {
      return lastPage && lastPage[lastPage.length - 1].eventId;
    },
    keepPreviousData: true,
  });
};

export default useRecentActivity;
