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
      const { svcRulesVoteRequests } = await listSvcRulesVoteRequests();
      return svcRulesVoteRequests;
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
      const { svcRulesVoteResults } = await listSvcRulesVoteResults(
        limit,
        query.actionName,
        query.requester,
        query.effectiveFrom,
        query.effectiveTo,
        query.executed
      );
      return svcRulesVoteResults;
    },
  });
};
