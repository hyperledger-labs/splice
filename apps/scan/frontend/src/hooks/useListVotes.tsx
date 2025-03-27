// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
// TODO(#7675) - do we need this model?
import { SvVote } from '@lfdecentralizedtrust/splice-common-frontend';
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useScanClient } from '@lfdecentralizedtrust/splice-common-frontend/scan-api';
import { useQuery, UseQueryResult } from '@tanstack/react-query';

import * as damlTypes from '@daml/types';
import { Vote, VoteRequest } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';
import { ContractId } from '@daml/types';

function getVoteStatus(votes: damlTypes.Map<string, Vote>): Vote[] {
  const allVotes: Vote[] = [];
  votes.forEach((v, _) => allVotes.push(v));
  return allVotes;
}

export const useListVotes = (contractIds: ContractId<VoteRequest>[]): UseQueryResult<SvVote[]> => {
  const scanClient = useScanClient();
  return useQuery({
    queryKey: ['listVoteRequestsByTrackingCid', contractIds],
    queryFn: async () => {
      if (contractIds.length === 0) {
        return [];
      }
      const response = await scanClient.listVoteRequestsByTrackingCid({
        vote_request_contract_ids: contractIds,
      });
      const requests = response.vote_requests.map(v => Contract.decodeOpenAPI(v, VoteRequest));
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
