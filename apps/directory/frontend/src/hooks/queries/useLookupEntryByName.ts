import { UseQueryResult, useQuery } from '@tanstack/react-query';
import { Contract, useDirectoryClient } from 'common-frontend';

import { DirectoryEntry } from '@daml.js/directory/lib/CN/Directory';

import { usePrimaryParty } from '..';

export class LookupEntryByNameError extends Error {
  type: 'assigned_to_other' | 'unknown';

  constructor(message: string, type: 'assigned_to_other' | 'unknown') {
    super(`LookupEntryByNameError (${type}): ${message}`);
    this.type = type;
  }
}

const useLookupEntryByName = (
  name?: string
): UseQueryResult<Contract<DirectoryEntry>, LookupEntryByNameError> => {
  const directoryClient = useDirectoryClient();
  const { data: primaryPartyId } = usePrimaryParty();

  return useQuery({
    queryKey: ['lookupEntryByName', name],
    queryFn: async () => {
      return directoryClient
        .lookupEntryByName(name!)
        .then(response => {
          if (response.payload.user !== primaryPartyId) {
            throw new LookupEntryByNameError('CNS name already taken', 'assigned_to_other');
          } else {
            return response;
          }
        })
        .catch(err => {
          throw new LookupEntryByNameError(JSON.stringify(err), 'unknown');
        });
    },
    retry: (failureCount: number, error: LookupEntryByNameError) => {
      if (error.type === 'assigned_to_other') {
        return false; // no point in retrying if we know the name is taken
      } else {
        return failureCount <= 3; // 3 retries is the react-query default
      }
    },
    enabled: !!primaryPartyId && !!name,
  });
};

export default useLookupEntryByName;
