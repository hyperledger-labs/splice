import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { PollingStrategy } from 'common-frontend-utils';
import { ApiException, CnsEntry, LookupEntryByNameResponse } from 'scan-openapi';

import { useScanClient } from './ScanClientContext';

const useLookupCnsEntryByName = (
  name: string,
  enabled: boolean = true,
  retryWhenNotFound: boolean = false,
  retry: number = 3
): UseQueryResult<CnsEntry> => {
  const scanClient = useScanClient();

  return useLookupCnsEntryByNameFromResponse(
    name => scanClient.lookupCnsEntryByName(name),
    name,
    enabled,
    retryWhenNotFound,
    retry
  );
};

export function useLookupCnsEntryByNameFromResponse(
  getResponse: (name: string) => Promise<LookupEntryByNameResponse>,
  name: string,
  enabled: boolean = true,
  retryWhenNotFound: boolean = false,
  retry: number = 3
): UseQueryResult<CnsEntry> {
  return useQuery({
    refetchInterval: PollingStrategy.NONE,
    queryKey: ['scan-api', 'lookupCnsEntryByName', CnsEntry, name],
    queryFn: async () => {
      try {
        const response = await getResponse(name);
        return response.entry;
      } catch (e: unknown) {
        if ((e as ApiException<undefined>).code === 404) {
          console.info(`No CNS entry for name ${name} found`);
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

export default useLookupCnsEntryByName;
