import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { Contract } from 'common-frontend-utils';
import { PollingStrategy } from 'common-frontend-utils';

import { BalanceUpdate, Group } from '@daml.js/splitwell/lib/Splice/Splitwell';

import { useSplitwellClient } from '../../contexts/SplitwellServiceContext';

export const useBalanceUpdates = (
  group: Contract<Group>,
  party: string
): UseQueryResult<Contract<BalanceUpdate>[]> => {
  const splitwellClient = useSplitwellClient();

  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: ['balanceUpdates', group, party],
    queryFn: async () => {
      const balanceUpdates = (
        await splitwellClient.listBalanceUpdates(
          party,
          group.payload.id.unpack,
          group.payload.owner
        )
      ).balance_updates;
      const updates = balanceUpdates.map(c => Contract.decodeOpenAPI(c, BalanceUpdate));
      // TODO(#10755) Remove those noisy logs once the issue is fixed.
      console.log(`balance updates: ${JSON.stringify(updates)}`);
      return updates;
    },
  });
};
