import { UseQueryResult, useQuery } from '@tanstack/react-query';
import { Contract, PollingStrategy, useScanClient } from 'common-frontend';

import { CnsEntry } from '@daml.js/cns/lib/CN/Cns';

import { toFullEntryName } from '../../utils';
import { usePrimaryParty } from './usePrimaryParty';

type LookupEntryResponse = {
  entryContract?: Contract<CnsEntry>;
};

const useLookupEntryByName = (
  name: string,
  suffix: string,
  retryWhenNotFound: boolean = false
): UseQueryResult<LookupEntryResponse> => {
  const scanClient = useScanClient();
  const primaryPartyId = usePrimaryParty();

  return useQuery({
    queryKey: ['lookupEntryByName', name, suffix],
    queryFn: async () => {
      return scanClient
        .lookupEntryByName(toFullEntryName(name, suffix))
        .then(response => ({
          entryContract: response,
        }))
        .catch(err => {
          if (err?.code === 404) {
            console.info(`Contract for directory entry ${name} does not exist`);
            if (retryWhenNotFound) {
              throw err;
            } else {
              return { entryContract: undefined };
            }
          } else {
            throw err;
          }
        });
    },
    refetchInterval: PollingStrategy.NONE,
    enabled: !!primaryPartyId && !!name,
    retry: 10,
  });
};

export default useLookupEntryByName;
