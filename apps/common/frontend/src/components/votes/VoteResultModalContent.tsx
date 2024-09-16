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
import VoteModalContent from './VoteModalContent';

interface VoteResultModalProps {
  handleClose: () => void;
  voteResult?: DsoRules_CloseVoteRequestResult;
}

export const VoteResultModalContent: React.FC<VoteResultModalProps> = ({
  handleClose,
  voteResult,
}) => {
  if (!voteResult) {
    return <>no voteResult defined</>;
  }

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
      voteRequestContractId={voteResult.request.trackingCid as ContractId<VoteRequest>}
      actionReq={voteResult.request.action}
      requester={voteResult.request.requester}
      reason={voteResult.request.reason}
      voteBefore={dayjs(voteResult.request.voteBefore).toDate()}
      rejectedVotes={rejectedVotes}
      acceptedVotes={acceptedVotes}
      handleClose={handleClose}
    />
  );
};

export default VoteResultModalContent;
