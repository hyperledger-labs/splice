// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useQuery, UseQueryResult } from '@tanstack/react-query';

import { Group } from '@daml.js/splitwell/lib/Splice/Splitwell';

import { useSplitwellClient } from '../../contexts/SplitwellServiceContext';

export interface Balances {
  [party: string]: string;
}

export const useBalances = (group: Contract<Group>, party: string): UseQueryResult<Balances> => {
  const splitwellClient = useSplitwellClient();

  return useQuery({
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
