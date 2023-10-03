import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { PollingStrategy } from 'common-frontend';
import { NodeStatus } from 'sv-openapi';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

export const useMediatorStatus = (): UseQueryResult<NodeStatus> => {
  const { getMediatorNodeStatus } = useSvAdminClient();
  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: ['getMediatorNodeStatus'],
    queryFn: async () => await getMediatorNodeStatus(),
  });
};
