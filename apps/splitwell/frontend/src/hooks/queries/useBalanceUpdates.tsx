// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
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
      return balanceUpdates.map(c => Contract.decodeOpenAPI(c.contract, BalanceUpdate));
    },
  });
};
