import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { Contract, PollingStrategy } from 'common-frontend-utils';

import {
  VoteRequest,
  VoteRequestResult,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';
import { List } from '@daml/types';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

export type ListVoteRequestResultParams = {
  actionName?: string;
  executed?: boolean;
  requester?: string;
  effectiveFrom?: string;
  effectiveTo?: string;
};

export const useListDsoRulesVoteRequests = (): UseQueryResult<Contract<VoteRequest>[]> => {
  const { listDsoRulesVoteRequests } = useSvAdminClient();
  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: ['listDsoRulesVoteRequests'],
    queryFn: async () => {
      const { dso_rules_vote_requests } = await listDsoRulesVoteRequests();
      return dso_rules_vote_requests.map(c => Contract.decodeOpenAPI(c, VoteRequest));
    },
  });
};

export const useListVoteRequestResult = (
  query: ListVoteRequestResultParams,
  limit: number = 10
): UseQueryResult<VoteRequestResult[]> => {
  const { listVoteRequestResults } = useSvAdminClient();
  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: [
      'listVoteRequestResults',
      VoteRequestResult,
      limit,
      query.actionName,
      query.executed,
      query.requester,
      query.effectiveFrom,
      query.effectiveTo,
    ],
    keepPreviousData: true,
    queryFn: async () => {
      const { dso_rules_vote_results } = await listVoteRequestResults(
        limit,
        query.actionName,
        query.requester,
        query.effectiveFrom,
        query.effectiveTo,
        query.executed
      );
      return List(VoteRequestResult).decoder.runWithException(dso_rules_vote_results);
    },
  });
};
