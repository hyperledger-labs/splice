// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { Contract, PollingStrategy } from 'common-frontend-utils';

import { OpenMiningRound } from '@daml.js/splice-amulet/lib/Splice/Round';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

export const useOpenMiningRounds = (): UseQueryResult<Contract<OpenMiningRound>[]> => {
  const { listOpenMiningRounds } = useSvAdminClient();
  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: ['listOpenMiningRounds'],
    queryFn: async () => {
      const { open_mining_rounds } = await listOpenMiningRounds();
      return open_mining_rounds;
    },
  });
};
