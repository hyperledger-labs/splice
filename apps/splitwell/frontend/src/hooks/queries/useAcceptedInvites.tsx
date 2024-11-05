// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { sameContracts } from 'common-frontend';
import { Contract } from 'common-frontend-utils';

import { AcceptedGroupInvite, Group } from '@daml.js/splitwell/lib/Splice/Splitwell';

import { useSplitwellClient } from '../../contexts/SplitwellServiceContext';

export const useAcceptedInvites = (
  group: Contract<Group>,
  party: string
): UseQueryResult<Contract<AcceptedGroupInvite>[]> => {
  const splitwellClient = useSplitwellClient();
  const groupId = group.payload.id.unpack;

  return useQuery({
    queryKey: ['acceptedInvites', groupId, party],
    queryFn: async () => {
      const invites = (await splitwellClient.listAcceptedGroupInvites(party, groupId))
        .accepted_group_invites;
      return invites.map(c => Contract.decodeOpenAPI(c, AcceptedGroupInvite));
    },
    structuralSharing: (oldData, newData) =>
      sameContracts(oldData || [], newData) ? oldData || [] : newData,
  });
};
