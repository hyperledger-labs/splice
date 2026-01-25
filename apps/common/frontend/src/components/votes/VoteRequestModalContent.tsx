// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useVotesHooks } from '@lfdecentralizedtrust/splice-common-frontend';
import { Loading, SvVote } from '@lfdecentralizedtrust/splice-common-frontend';
import dayjs from 'dayjs';
import React, { useEffect } from 'react';

import { VoteRequest } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { ContractId } from '@daml/types';

import VoteModalContent from './VoteModalContent';

interface VoteRequestModalProps {
  voteRequestContractId: ContractId<VoteRequest>;
  handleClose: () => void;
  voteForm?: (
    voteRequestContractId: ContractId<VoteRequest>,
    currentSvVote: SvVote | undefined
  ) => React.ReactNode;
  getMemberName: (partyId: string) => string;
  expiresAt?: Date;
  effectiveAt?: Date;
}

const VoteRequestModalContent: React.FC<VoteRequestModalProps> = ({
  voteRequestContractId,
  handleClose,
  voteForm,
  getMemberName,
  expiresAt,
  effectiveAt,
}) => {
  const votesHooks = useVotesHooks();
  const voteRequestQuery = votesHooks.useVoteRequest(voteRequestContractId);
  const voteRequests = votesHooks.useListDsoRulesVoteRequests();
  const votesQuery = votesHooks.useListVotes([voteRequestContractId]);

  // allVotes being empty means that the vote request has been executed, as the initiator of the request must vote on his proposition. Therefore, we can close the modal.
  useEffect(() => {
    if (votesQuery.data?.length === 0) {
      handleClose();
    }
  }, [votesQuery, handleClose]);

  const dsoInfosQuery = votesHooks.useDsoInfos();
  const svPartyId = dsoInfosQuery.data?.svPartyId;

  if (voteRequestQuery.isLoading || voteRequests.isLoading) {
    return <Loading />;
  }

  if (voteRequestQuery.isError || voteRequests.isError) {
    return <p>Error, something went wrong.</p>;
  }

  if (!voteRequestQuery.data || !voteRequests.data) {
    return <p>no VoteRequest contractId is specified</p>;
  }

  if (votesQuery.isLoading) {
    return <Loading />;
  }

  if (votesQuery.isError) {
    return <p>Error, something went wrong.</p>;
  }

  if (!votesQuery.data) {
    return <p>no VoteRequest contractId is specified</p>;
  }

  const curSvVote: SvVote | undefined = votesQuery.data.find(v => v.voter === svPartyId);

  const allVotes = [...votesQuery.data].sort((a, b) => {
    return b.expiresAt.valueOf() - a.expiresAt.valueOf();
  });

  const acceptedVotes = allVotes.filter(v => v.accept);
  const rejectedVotes = allVotes.filter(v => !v.accept);

  return (
    <VoteModalContent
      voteRequestContractId={voteRequestContractId}
      actionReq={voteRequestQuery.data.payload.action}
      requester={voteRequestQuery.data.payload.requester}
      getMemberName={getMemberName}
      reason={voteRequestQuery.data.payload.reason}
      voteBefore={dayjs(voteRequestQuery.data.payload.voteBefore).toDate()}
      rejectedVotes={rejectedVotes}
      acceptedVotes={acceptedVotes}
      voteForm={voteForm}
      curSvVote={curSvVote}
      expiresAt={expiresAt}
      effectiveAt={effectiveAt}
    />
  );
};

export default VoteRequestModalContent;
