// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { Contract } from 'common-frontend-utils';
import { PollingStrategy } from 'common-frontend-utils';

import { Group } from '@daml.js/splitwell/lib/Splice/Splitwell';

import { useSplitwellClient } from '../../contexts/SplitwellServiceContext';

export interface Balances {
  [party: string]: string;
}

export const useBalances = (group: Contract<Group>, party: string): UseQueryResult<Balances> => {
  const splitwellClient = useSplitwellClient();

  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: ['balances', group, party],
    queryFn: async () => {
      const balanceMap = (
        await splitwellClient.listBalances(party, group.payload.id.unpack, group.payload.owner)
      ).balances;
      let balances: Balances = {};
      [group.payload.owner].concat(group.payload.members).forEach(p => {
        if (p !== party) {
          const balance: string | undefined = balanceMap[p];
          if (balance) {
            balances[p] = balance;
          } else {
            balances[p] = '0.0';
          }
        }
      });
      return balances;
    },
  });
};
