import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { Contract, PollingStrategy } from 'common-frontend-utils';

import { VoteRequest } from '@daml.js/dso-governance/lib/CN/DsoRules/module';
import { ContractId } from '@daml/types';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

export const useVoteRequest = (
  contractId: ContractId<VoteRequest> | undefined
): UseQueryResult<Contract<VoteRequest> | undefined> => {
  const { lookupDsoRulesVoteRequest } = useSvAdminClient();
  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: ['listDsoRulesVoteRequests', contractId],
    queryFn: async () =>
      contractId ? (await lookupDsoRulesVoteRequest(contractId)).dso_rules_vote_request : undefined,
  });
};
