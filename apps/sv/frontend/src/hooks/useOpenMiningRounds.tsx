import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { Contract, PollingStrategy } from 'common-frontend-utils';

import { OpenMiningRound } from '@daml.js/canton-coin/lib/CC/Round';

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
