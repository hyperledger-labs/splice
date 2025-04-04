// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { sameAssignedContracts } from '@lfdecentralizedtrust/splice-common-frontend';
import { AssignedContract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useQuery, UseQueryResult } from '@tanstack/react-query';

import { GroupInvite } from '@daml.js/splitwell/lib/Splice/Splitwell';

import { useSplitwellClient } from '../../contexts/SplitwellServiceContext';

export const useGroupInvites = (party: string): UseQueryResult<AssignedContract<GroupInvite>[]> => {
  const splitwellClient = useSplitwellClient();

  return useQuery({
    queryKey: ['groupInvites', party],
    queryFn: async () => {
      const groupInvites = (await splitwellClient.listGroupInvites(party)).group_invites;
      return groupInvites.flatMap(c => {
        const d = AssignedContract.decodeContractWithState(c, GroupInvite);
        return d === undefined ? [] : [d];
      });
    },
    structuralSharing: (oldData, newData) =>
      sameAssignedContracts(oldData || [], newData) ? oldData || [] : newData,
  });
};
