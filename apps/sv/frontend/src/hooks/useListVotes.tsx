import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { Contract, PollingStrategy } from 'common-frontend-utils';

import * as damlTypes from '@daml/types';
import { Vote2, VoteRequest2 } from '@daml.js/svc-governance/lib/CN/SvcRules/module';
import { ContractId } from '@daml/types';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';
// TODO(#7675) - do we need this model?
import { SvVote } from '../models/models';

function getVoteStatus(votes: damlTypes.Map<string, Vote2>): Vote2[] {
  const allVotes: Vote2[] = [];
  votes.forEach((v, _) => allVotes.push(v));
  return allVotes;
}

export const useListVotes = (contractIds: ContractId<VoteRequest2>[]): UseQueryResult<SvVote[]> => {
  const { listVoteRequests2ByTrackingCid } = useSvAdminClient();
  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: ['listVoteRequests2ByTrackingCid', contractIds],
    queryFn: async () => {
      if (contractIds.length === 0) {
        return [];
      }
      const { vote_requests } = await listVoteRequests2ByTrackingCid(contractIds);
      const requests = vote_requests.map(v => Contract.decodeOpenAPI(v, VoteRequest2));
      return requests.flatMap(vr =>
        getVoteStatus(vr.payload.votes).map(vote => {
          return {
            requestCid: vr.payload.trackingCid ? vr.payload.trackingCid : vr.contractId,
            voter: vote.sv,
            accept: vote.accept,
            reason: {
              url: vote.reason.url,
              body: vote.reason.body,
            },
            expiresAt: new Date(vr.payload.voteBefore),
          };
        })
      );
    },
  });
};
