import { UseQueryResult, useQuery } from '@tanstack/react-query';
import { Contract, useDirectoryClient } from 'common-frontend';

import { DirectoryEntry } from '@daml.js/directory/lib/CN/Directory';

import { usePrimaryParty } from '..';

type LookupEntryResponse = {
  entryContract?: Contract<DirectoryEntry>;
};

const useLookupEntryByName = (name?: string): UseQueryResult<LookupEntryResponse> => {
  const directoryClient = useDirectoryClient();
  const { data: primaryPartyId } = usePrimaryParty();

  return useQuery({
    queryKey: ['lookupEntryByName', name],
    queryFn: async () => {
      return directoryClient
        .lookupEntryByName(name!)
        .then(response => ({
          entryContract: response,
        }))
        .catch(err => {
          if (err?.code === 404) {
            console.info(`Contract for directory entry ${name} does not exist`);
            return { entryContract: undefined };
          } else {
            throw err;
          }
        });
    },
    enabled: !!primaryPartyId && !!name,
  });
};

export default useLookupEntryByName;
