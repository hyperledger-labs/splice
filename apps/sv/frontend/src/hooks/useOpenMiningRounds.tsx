import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { Contract } from 'common-frontend';
import { OpenMiningRound } from 'common-frontend/daml.js/canton-coin-0.1.0/lib/CC/Round/module';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

export const useOpenMiningRounds = (): UseQueryResult<Contract<OpenMiningRound>[]> => {
  const { listOpenMiningRounds } = useSvAdminClient();
  return useQuery({
    queryKey: ['listOpenMiningRounds'],
    queryFn: async () => {
      const { open_mining_rounds } = await listOpenMiningRounds();
      return open_mining_rounds;
    },
  });
};
