// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { PollingStrategy } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import {
  ApiException,
  AnsEntry,
  LookupEntryByNameResponse,
} from '@lfdecentralizedtrust/scan-openapi';

import { useScanClient } from './ScanClientContext';

const useLookupAnsEntryByName = (
  name: string,
  enabled: boolean = true,
  retryWhenNotFound: boolean = false,
  retry: number = 3
): UseQueryResult<AnsEntry> => {
  const scanClient = useScanClient();

  return useLookupAnsEntryByNameFromResponse(
    name => scanClient.lookupAnsEntryByName(name),
    name,
    enabled,
    retryWhenNotFound,
    retry
  );
};

export function useLookupAnsEntryByNameFromResponse(
  getResponse: (name: string) => Promise<LookupEntryByNameResponse>,
  name: string,
  enabled: boolean = true,
  retryWhenNotFound: boolean = false,
  retry: number = 3
): UseQueryResult<AnsEntry> {
  return useQuery({
    refetchInterval: PollingStrategy.NONE,
    queryKey: ['scan-api', 'lookupAnsEntryByName', AnsEntry, name],
    queryFn: async () => {
      try {
        const response = await getResponse(name);
        return response.entry;
      } catch (e: unknown) {
        if ((e as ApiException<undefined>).code === 404) {
          console.info(`No name service entry for name ${name} found`);
          if (retryWhenNotFound) {
            throw e;
          } else {
            return null;
          }
        } else {
          console.info(`what ${e} found`);
          throw e;
        }
      }
    },
    enabled: name !== '' && enabled,
    retry,
  });
}

export default useLookupAnsEntryByName;
