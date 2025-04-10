// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import dayjs from 'dayjs';
import React from 'react';

import {
  DsoRules_CloseVoteRequestResult,
  VoteRequest,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';
import { ContractId } from '@daml/types';

import { SvVote } from '../../models';
import { VoteResultModalState } from './ListVoteRequests';
import VoteModalContent from './VoteModalContent';

interface VoteResultModalStateInterface {
  voteResultModalState: VoteResultModalState;
  getMemberName: (partyId: string) => string;
}

export const VoteResultModalContent: React.FC<VoteResultModalStateInterface> = ({
  voteResultModalState,
  getMemberName,
}) => {
  if (!voteResultModalState.open) {
    return <>no voteResultModalState defined</>;
  }

  const { voteResult, tableType, effectiveAt } = voteResultModalState;
  const encodedResult = DsoRules_CloseVoteRequestResult.encode(
    voteResult
  ) as DsoRules_CloseVoteRequestResult;

  const allVotes = voteResult.request.votes.entriesArray();

  const acceptedVotes: SvVote[] = allVotes
    .filter(v => v[1].accept)
    .map(v => ({
      reason: v[1].reason,
      requestCid: voteResult.request.trackingCid as ContractId<VoteRequest>,
      voter: v[0],
      accept: v[1].accept,
      expiresAt: dayjs(voteResult.request.voteBefore).toDate(),
    }));
  const rejectedVotes: SvVote[] = allVotes
    .filter(v => !v[1].accept)
    .map(v => ({
      reason: v[1].reason,
      requestCid: voteResult.request.trackingCid as ContractId<VoteRequest>,
      voter: v[0],
      accept: v[1].accept,
      expiresAt: dayjs(voteResult.request.voteBefore).toDate(),
    }));

  return (
    <VoteModalContent
      voteRequestContractId={encodedResult.request.trackingCid as ContractId<VoteRequest>}
      actionReq={encodedResult.request.action}
      requester={encodedResult.request.requester}
      getMemberName={getMemberName}
      reason={encodedResult.request.reason}
      voteBefore={dayjs(encodedResult.request.voteBefore).toDate()}
      rejectedVotes={rejectedVotes}
      acceptedVotes={acceptedVotes}
      tableType={tableType}
      effectiveAt={effectiveAt}
    />
  );
};
