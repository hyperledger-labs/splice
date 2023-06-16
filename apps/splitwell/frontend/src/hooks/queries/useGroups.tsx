import { QueryClient, UseQueryResult, useQuery } from '@tanstack/react-query';
import { ReadyContract } from 'common-frontend';
import {
  ListGroupsRequest,
  SplitwellContext,
} from 'common-protobuf/com/daml/network/splitwell/v0/splitwell_service_pb';

import { Group } from '@daml.js/splitwell/lib/CN/Splitwell';

import { useSplitwellClient } from '../../contexts/SplitwellServiceContext';

const groupsQueryKey = (party: string) => ['splitwell-api', 'groups', party];

// We read groups directly from the client within mutation function. That ensures
// that on a retry we get a potentially more recent version with an updated contract id.
export const getGroups = (party: string, queryClient: QueryClient): ReadyContract<Group>[] =>
  queryClient.getQueryData(groupsQueryKey(party)) as ReadyContract<Group>[];

export const useGroups = (party: string): UseQueryResult<ReadyContract<Group>[]> => {
  const splitwellClient = useSplitwellClient();
  return useQuery({
    queryKey: groupsQueryKey(party),
    queryFn: async () => {
      const newGroups = (
        await splitwellClient.listGroups(
          new ListGroupsRequest().setContext(new SplitwellContext().setUserPartyId(party)),
          undefined
        )
      ).getGroupsList();
      return newGroups.flatMap(g => {
        // don't display groups in-flight from one domain to another
        const d = ReadyContract.decodeContractWithState(g, Group);
        return d === undefined ? [] : [d];
      });
    },
  });
};
