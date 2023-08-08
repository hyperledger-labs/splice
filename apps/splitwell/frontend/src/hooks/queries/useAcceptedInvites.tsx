import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { Contract, sameContracts } from 'common-frontend';

import { AcceptedGroupInvite, Group } from '@daml.js/splitwell/lib/CN/Splitwell';

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
        .acceptedGroupInvites;
      return invites.map(c => Contract.decodeOpenAPI(c, AcceptedGroupInvite));
    },
    structuralSharing: (oldData, newData) =>
      sameContracts(oldData || [], newData) ? oldData || [] : newData,
  });
};
