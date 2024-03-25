import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { PollingStrategy } from 'common-frontend-utils';
import { ApiException, CnsEntry, LookupEntryByPartyResponse } from 'scan-openapi';

import { useScanClient } from './ScanClientContext';

const useLookupCnsEntryByParty = (party?: string): UseQueryResult<CnsEntry> => {
  const scanClient = useScanClient();

  return useLookupCnsEntryByPartyFromResponse(p => scanClient.lookupCnsEntryByParty(p), party);
};

export function useLookupCnsEntryByPartyFromResponse(
  getResponse: (party: string) => Promise<LookupEntryByPartyResponse>,
  party?: string
): UseQueryResult<CnsEntry> {
  return useQuery({
    refetchInterval: PollingStrategy.NONE,
    queryKey: ['scan-api', 'lookupCnsEntryByParty', CnsEntry, party],
    queryFn: async () => {
      try {
        const response = await getResponse(party!);
        return response.entry;
      } catch (e: unknown) {
        if ((e as ApiException<undefined>).code === 404) {
          console.debug(`No CNS entry for party ${party} found`);
          return null;
        } else {
          throw e;
        }
      }
    },
    enabled: !!party,
  });
}

export default useLookupCnsEntryByParty;
