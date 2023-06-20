import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { NodeStatus } from 'sv-openapi';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

export const useMediatorStatus = (): UseQueryResult<NodeStatus> => {
  const { getMediatorNodeStatus } = useSvAdminClient();
  return useQuery({
    queryKey: ['getMediatorNodeStatus'],
    queryFn: async () => await getMediatorNodeStatus(),
  });
};
