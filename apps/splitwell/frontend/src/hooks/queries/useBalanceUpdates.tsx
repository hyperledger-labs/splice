import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { Contract } from 'common-frontend';

import { BalanceUpdate, Group } from '@daml.js/splitwell/lib/CN/Splitwell';

import { useSplitwellClient } from '../../contexts/SplitwellServiceContext';

export const useBalanceUpdates = (
  group: Contract<Group>,
  party: string
): UseQueryResult<Contract<BalanceUpdate>[]> => {
  const splitwellClient = useSplitwellClient();

  return useQuery({
    queryKey: ['balanceUpdates', group, party],
    queryFn: async () => {
      const balanceUpdates = (
        await splitwellClient.listBalanceUpdates(
          party,
          group.payload.id.unpack,
          group.payload.owner
        )
      ).balance_updates;
      return balanceUpdates.reverse().map(c => Contract.decodeOpenAPI(c, BalanceUpdate));
    },
  });
};
