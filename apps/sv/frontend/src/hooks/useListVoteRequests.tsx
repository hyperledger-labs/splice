import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { Contract } from 'common-frontend';

import { VoteRequest, VoteResult } from '@daml.js/svc-governance/lib/CN/SvcRules/module';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

export type ListSvcRulesVoteResultsParams = {
  actionName?: string;
  executed?: boolean;
  requester?: string;
  effectiveFrom?: string;
  effectiveTo?: string;
};

export const useListSvcRulesVoteRequests = (): UseQueryResult<Contract<VoteRequest>[]> => {
  const { listSvcRulesVoteRequests } = useSvAdminClient();
  return useQuery({
    queryKey: ['listSvcRulesVoteRequests'],
    queryFn: async () => {
      const { svc_rules_vote_requests } = await listSvcRulesVoteRequests();
      return svc_rules_vote_requests.map(c => Contract.decodeOpenAPI(c, VoteRequest));
    },
  });
};

export const useListSvcRulesVoteResults = (
  query: ListSvcRulesVoteResultsParams,
  limit: number = 10
): UseQueryResult<VoteResult[]> => {
  const { listSvcRulesVoteResults } = useSvAdminClient();
  return useQuery({
    queryKey: [
      'listSvcRulesVoteResults',
      limit,
      query.actionName,
      query.executed,
      query.requester,
      query.effectiveFrom,
      query.effectiveTo,
    ],
    keepPreviousData: true,
    queryFn: async () => {
      const { svc_rules_vote_results } = await listSvcRulesVoteResults(
        limit,
        query.actionName,
        query.requester,
        query.effectiveFrom,
        query.effectiveTo,
        query.executed
      );
      return svc_rules_vote_results;
    },
  });
};
