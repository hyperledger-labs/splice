import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { Contract } from 'common-frontend';

import { Group } from '@daml.js/splitwell/lib/CN/Splitwell';

import { useSplitwellClient } from '../../contexts/SplitwellServiceContext';

export const useBalances = (group: Contract<Group>, party: string): UseQueryResult<string[]> => {
  const splitwellClient = useSplitwellClient();

  return useQuery({
    queryKey: ['balances', group, party],
    queryFn: async () => {
      const balanceMap = (
        await splitwellClient.listBalances(party, group.payload.id.unpack, group.payload.owner)
      ).balances;
      let balances = new Map<string, string>();
      [group.payload.owner].concat(group.payload.members).forEach(p => {
        if (p !== party) {
          const balance: string | undefined = balanceMap[p];
          if (balance) {
            balances.set(p, balance);
          } else {
            balances.set(p, '0.0');
          }
        }
      });
      return balances;
    },
  });
};
