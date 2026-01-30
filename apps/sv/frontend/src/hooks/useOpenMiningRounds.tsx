// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useQuery, UseQueryResult } from '@tanstack/react-query';

import { OpenMiningRound } from '@daml.js/splice-amulet/lib/Splice/Round';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

export const useOpenMiningRounds = (): UseQueryResult<Contract<OpenMiningRound>[]> => {
  const { listOpenMiningRounds } = useSvAdminClient();
  return useQuery({
    queryKey: ['listOpenMiningRounds'],
    queryFn: async () => {
      const { open_mining_rounds } = await listOpenMiningRounds();
      return open_mining_rounds.map(r => Contract.decodeOpenAPI(r, OpenMiningRound));
    },
  });
};
