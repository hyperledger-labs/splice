import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { ListEntriesResponse } from 'scan-openapi';

import { CnsEntry } from '@daml.js/cns/lib/CN/Cns/';

import { PollingStrategy } from '../..';
import { Contract } from '../../utils';
import { useScanClient } from './ScanClientContext';

const useListCnsEntries = (
  pageSize: number,
  namePrefix?: string
): UseQueryResult<Contract<CnsEntry>[]> => {
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
): UseQueryResult<Contract<CnsEntry>[]> {
  return useQuery({
    refetchInterval: PollingStrategy.NONE,
    queryKey: ['scan-api', 'lookupCnsEntryByName', pageSize, namePrefix],
    queryFn: async () => {
      const response = await getResponse(pageSize, namePrefix);
      return response.entries.map(contract => Contract.decodeOpenAPI(contract, CnsEntry));
    },
  });
}

export default useListCnsEntries;
