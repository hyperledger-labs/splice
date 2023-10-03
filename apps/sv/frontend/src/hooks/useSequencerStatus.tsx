import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { PollingStrategy } from 'common-frontend';
import { NodeStatus } from 'sv-openapi';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

export const useSequencerStatus = (): UseQueryResult<NodeStatus> => {
  const { getSequencerNodeStatus } = useSvAdminClient();
  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: ['getSequencerNodeStatus'],
    queryFn: async () => await getSequencerNodeStatus(),
  });
};
