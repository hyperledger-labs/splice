import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { NodeStatus } from 'sv-openapi';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

export const useSequencerStatus = (): UseQueryResult<NodeStatus> => {
  const { getSequencerNodeStatus } = useSvAdminClient();
  return useQuery({
    queryKey: ['getSequencerNodeStatus'],
    queryFn: async () => await getSequencerNodeStatus(),
  });
};
