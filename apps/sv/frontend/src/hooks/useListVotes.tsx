import { useQuery, UseQueryResult } from '@tanstack/react-query';

import { VoteRequest } from '@daml.js/svc-governance/lib/CN/SvcRules/module';
import { ContractId } from '@daml/types';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';
import { SvVote } from '../models/models';

export const useListVotes = (contractIds: ContractId<VoteRequest>[]): UseQueryResult<SvVote[]> => {
  const { listVotesByVoteRequests } = useSvAdminClient();
  return useQuery({
    queryKey: ['listVotesByVoteRequests', contractIds],
    queryFn: async () => {
      if (contractIds.length === 0) {
        return [];
      }
      const { svc_rules_votes } = await listVotesByVoteRequests(contractIds);
      return svc_rules_votes.map(vote => {
        return {
          contractId: vote.contract_id,
          requestCid: vote.payload.requestCid,
          voter: vote.payload.voter,
          accept: vote.payload.accept,
          reason: {
            url: vote.payload.reason.url,
            body: vote.payload.reason.body,
          },
          expiresAt: new Date(vote.payload.expiresAt),
        };
      });
    },
  });
};
