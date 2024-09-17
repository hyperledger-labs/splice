// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { PollingStrategy } from 'common-frontend-utils';
import { ApiException, AnsEntry, LookupEntryByPartyResponse } from 'scan-openapi';

import { useScanClient } from './ScanClientContext';

const useLookupAnsEntryByParty = (party?: string): UseQueryResult<AnsEntry> => {
  const scanClient = useScanClient();

  return useLookupAnsEntryByPartyFromResponse(p => scanClient.lookupAnsEntryByParty(p), party);
};

export function useLookupAnsEntryByPartyFromResponse(
  getResponse: (party: string) => Promise<LookupEntryByPartyResponse>,
  party?: string
): UseQueryResult<AnsEntry> {
  return useQuery({
    refetchInterval: PollingStrategy.NONE,
    queryKey: ['scan-api', 'lookupAnsEntryByParty', AnsEntry, party],
    queryFn: async () => {
      try {
        const response = await getResponse(party!);
        return response.entry;
      } catch (e: unknown) {
        if ((e as ApiException<undefined>).code === 404) {
          console.debug(`No name service entry for party ${party} found`);
          return null;
        } else {
          throw e;
        }
      }
    },
    enabled: !!party,
  });
}

export default useLookupAnsEntryByParty;
