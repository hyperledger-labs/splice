import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { Contract } from 'common-frontend';

import { VoteRequest } from '@daml.js/svc-governance/lib/CN/SvcRules/module';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

export const useListSvcRulesVoteRequests = (): UseQueryResult<Contract<VoteRequest>[]> => {
  const { listSvcRulesVoteRequests } = useSvAdminClient();
  return useQuery({
    queryKey: ['listSvcRulesVoteRequests'],
    queryFn: async () => {
      const { svcRulesVoteRequests } = await listSvcRulesVoteRequests();
      return svcRulesVoteRequests;
    },
  });
};
