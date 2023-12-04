import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { ApiException } from 'scan-openapi';

import { CnsEntry } from '@daml.js/cns/lib/CN/Cns/';

import { PollingStrategy } from '../..';
import { Contract } from '../../utils';
import { useScanClient } from './ScanClientContext';

const useLookupCnsEntryByName = (
  name: string,
  enabled: boolean = true,
  retryWhenNotFound: boolean = false,
  retry: number = 3
): UseQueryResult<Contract<CnsEntry>> => {
  const scanClient = useScanClient();

  return useQuery({
    refetchInterval: PollingStrategy.NONE,
    queryKey: ['scan-api', 'lookupCnsEntryByName', CnsEntry, name],
    queryFn: async () => {
      try {
        const response = await scanClient.lookupCnsEntryByName(name);
        return Contract.decodeOpenAPI(response.entry, CnsEntry);
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
};

export default useLookupCnsEntryByName;
