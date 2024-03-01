import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { Contract, PollingStrategy } from 'common-frontend-utils';

import { VoteRequest2 } from '@daml.js/svc-governance/lib/CN/SvcRules/module';
import { ContractId } from '@daml/types';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

export const useVoteRequest = (
  contractId: ContractId<VoteRequest2> | undefined
): UseQueryResult<Contract<VoteRequest2> | undefined> => {
  const { lookupSvcRulesVoteRequest2 } = useSvAdminClient();
  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: ['listSvcRulesVoteRequests2', contractId],
    queryFn: async () =>
      contractId
        ? (await lookupSvcRulesVoteRequest2(contractId)).svc_rules_vote_request
        : undefined,
  });
};
