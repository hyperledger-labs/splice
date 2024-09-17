// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { PollingStrategy } from 'common-frontend-utils';
import { AnsEntry, ListEntriesResponse } from 'scan-openapi';

import { useScanClient } from './ScanClientContext';

const useListAnsEntries = (pageSize: number, namePrefix?: string): UseQueryResult<AnsEntry[]> => {
  const scanClient = useScanClient();
  return useListAnsEntriesFromResponse(
    (pageSize, namePrefix) => scanClient.listAnsEntries(pageSize, namePrefix),
    pageSize,
    namePrefix
  );
};

export function useListAnsEntriesFromResponse(
  getResponse: (pageSize: number, namePrefix?: string) => Promise<ListEntriesResponse>,
  pageSize: number,
  namePrefix?: string
): UseQueryResult<AnsEntry[]> {
  return useQuery({
    refetchInterval: PollingStrategy.NONE,
    queryKey: ['scan-api', 'lookupAnsEntryByName', pageSize, namePrefix],
    queryFn: async () => {
      const response = await getResponse(pageSize, namePrefix);
      return response.entries;
    },
  });
}

export default useListAnsEntries;
