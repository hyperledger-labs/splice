import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { AssignedContract, sameAssignedContracts } from 'common-frontend';

import { GroupInvite } from '@daml.js/splitwell/lib/CN/Splitwell';

import { useSplitwellClient } from '../../contexts/SplitwellServiceContext';

export const useGroupInvites = (party: string): UseQueryResult<AssignedContract<GroupInvite>[]> => {
  const splitwellClient = useSplitwellClient();

  return useQuery({
    queryKey: ['groupInvites', party],
    queryFn: async () => {
      const groupInvites = (await splitwellClient.listGroupInvites(party)).groupInvites;
      return groupInvites.flatMap(c => {
        const d = AssignedContract.decodeContractWithState(c, GroupInvite);
        return d === undefined ? [] : [d];
      });
    },
    structuralSharing: (oldData, newData) =>
      sameAssignedContracts(oldData || [], newData) ? oldData || [] : newData,
  });
};
