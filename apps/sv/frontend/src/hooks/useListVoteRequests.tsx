import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { Contract, PollingStrategy } from 'common-frontend-utils';

import { VoteRequest2, VoteRequestResult2 } from '@daml.js/svc-governance/lib/CN/SvcRules/module';
import { List } from '@daml/types';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

export type ListVoteRequestResult2Params = {
  actionName?: string;
  executed?: boolean;
  requester?: string;
  effectiveFrom?: string;
  effectiveTo?: string;
};

export const useListSvcRulesVoteRequests = (): UseQueryResult<Contract<VoteRequest2>[]> => {
  const { listSvcRulesVoteRequests2 } = useSvAdminClient();
  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: ['listSvcRulesVoteRequests2'],
    queryFn: async () => {
      const { svc_rules_vote_requests } = await listSvcRulesVoteRequests2();
      return svc_rules_vote_requests.map(c => Contract.decodeOpenAPI(c, VoteRequest2));
    },
  });
};

export const useListVoteRequestResult2 = (
  query: ListVoteRequestResult2Params,
  limit: number = 10
): UseQueryResult<VoteRequestResult2[]> => {
  const { listVoteRequestResults2 } = useSvAdminClient();
  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: [
      'listVoteRequestResults2',
      VoteRequestResult2,
      limit,
      query.actionName,
      query.executed,
      query.requester,
      query.effectiveFrom,
      query.effectiveTo,
    ],
    keepPreviousData: true,
    queryFn: async () => {
      const { svc_rules_vote_results } = await listVoteRequestResults2(
        limit,
        query.actionName,
        query.requester,
        query.effectiveFrom,
        query.effectiveTo,
        query.executed
      );
      return List(VoteRequestResult2).decoder.runWithException(svc_rules_vote_results);
    },
  });
};
