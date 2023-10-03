import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { Contract, PollingStrategy } from 'common-frontend';

import { VoteRequest } from '@daml.js/svc-governance/lib/CN/SvcRules/module';
import { ContractId } from '@daml/types';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

export const useVoteRequest = (
  contractId: ContractId<VoteRequest> | undefined
): UseQueryResult<Contract<VoteRequest> | undefined> => {
  const { lookupSvcRulesVoteRequest } = useSvAdminClient();
  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: ['listSvcRulesVoteRequests', contractId],
    queryFn: async () =>
      contractId ? (await lookupSvcRulesVoteRequest(contractId)).svc_rules_vote_request : undefined,
  });
};
