// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { AssignedContract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { QueryClient, UseQueryResult, useQuery } from '@tanstack/react-query';

import { Group } from '@daml.js/splitwell/lib/Splice/Splitwell';

import { useSplitwellClient } from '../../contexts/SplitwellServiceContext';

const groupsQueryKey = (party: string) => ['splitwell-api', 'groups', party];

// We read groups directly from the client within mutation function. That ensures
// that on a retry we get a potentially more recent version with an updated contract id.
export const getGroups = (party: string, queryClient: QueryClient): AssignedContract<Group>[] =>
  queryClient.getQueryData(groupsQueryKey(party)) as AssignedContract<Group>[];

export const useGroups = (party: string): UseQueryResult<AssignedContract<Group>[]> => {
  const splitwellClient = useSplitwellClient();
  return useQuery({
    queryKey: groupsQueryKey(party),
    queryFn: async () => {
      const newGroups = (await splitwellClient.listGroups(party)).groups;
      return newGroups.flatMap(g => {
        // don't display groups in-flight from one domain to another
        const d = AssignedContract.decodeContractWithState(g, Group);
        return d === undefined ? [] : [d];
      });
    },
  });
};
