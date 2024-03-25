import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { PollingStrategy } from 'common-frontend-utils';
import { CnsEntry, ListEntriesResponse } from 'scan-openapi';

import { useScanClient } from './ScanClientContext';

const useListCnsEntries = (pageSize: number, namePrefix?: string): UseQueryResult<CnsEntry[]> => {
  const scanClient = useScanClient();
  return useListCnsEntriesFromResponse(
    (pageSize, namePrefix) => scanClient.listCnsEntries(pageSize, namePrefix),
    pageSize,
    namePrefix
  );
};

export function useListCnsEntriesFromResponse(
  getResponse: (pageSize: number, namePrefix?: string) => Promise<ListEntriesResponse>,
  pageSize: number,
  namePrefix?: string
): UseQueryResult<CnsEntry[]> {
  return useQuery({
    refetchInterval: PollingStrategy.NONE,
    queryKey: ['scan-api', 'lookupCnsEntryByName', pageSize, namePrefix],
    queryFn: async () => {
      const response = await getResponse(pageSize, namePrefix);
      return response.entries;
    },
  });
}

export default useListCnsEntries;
