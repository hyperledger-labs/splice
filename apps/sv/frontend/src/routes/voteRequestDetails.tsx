// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import React from 'react';
import { useParams } from 'react-router-dom';
import { ContractId } from '@daml/types';
import { VoteRequest } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import {
  ProposalDetails,
  ProposalVote,
  ProposalVotingInformation,
  SupportedActionTag,
} from '../utils/types';
import { Typography } from '@mui/material';
import { useSvConfig } from '../utils';
import {
  actionTagToTitle,
  buildProposal,
  getActionValue,
  getVoteResultStatus,
} from '../utils/governance';
import { useDsoInfos } from '../contexts/SvContext';
import dayjs from 'dayjs';
import { dateTimeFormatISO } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useVoteRequestResultByCid } from '../hooks/useVoteRequestResultByCid';
import { Loading } from '@lfdecentralizedtrust/splice-common-frontend';
import { ProposalDetailsContent } from '../components/governance/ProposalDetailsContent';

export const VoteRequestDetails: React.FC = () => {
  const { contractId } = useParams();
  const ccid = contractId || '';

  const svConfig = useSvConfig();
  const amuletName = svConfig.spliceInstanceNames.amuletName;

  const dsoInfosQuery = useDsoInfos();

  const { hasVoteRequest, hasVoteResult, voteRequest, voteResult, isPending } =
    useVoteRequestResultByCid(contractId as ContractId<VoteRequest>);

  if (dsoInfosQuery.isPending && isPending) {
    return <Loading />;
  }

  // if (!isComplete) {
  //   return <Typography variant="body1">Error, something went wrong.</Typography>;
  // }

  const request = hasVoteRequest
    ? voteRequest?.payload
    : hasVoteResult
      ? voteResult?.request
      : undefined;

  if (!request) {
    return (
      <Typography variant="body1">
        Error, something went wrong. Unable to find the vote request with Contract ID {contractId}.
      </Typography>
    );
  }

  const svPartyId = dsoInfosQuery.data?.svPartyId || '';
  const allSvs = dsoInfosQuery.data?.dsoRules.payload.svs.entriesArray().map(e => e[0]) || [];
  const amuletOrDsoAction = getActionValue(request.action);

  // check that amuletOrDsoAction is a supported action
  if (
    !amuletOrDsoAction ||
    Object.keys(actionTagToTitle(amuletName)).indexOf(amuletOrDsoAction.tag) === -1
  ) {
    return <Typography variant="body1">Error, something went wrong. Unsupported Action</Typography>;
  }

  const action = amuletOrDsoAction.tag as SupportedActionTag;
  const actionName = actionTagToTitle(amuletName)[action];
  // TODO: There doesn't seem to be a way to fetch the createdAt date of a vote result as it's not a contract.
  const createdAt = voteRequest ? dayjs(voteRequest.createdAt).format(dateTimeFormatISO) : '';

  // build the object for the vote request details props
  const proposalDetails: ProposalDetails = {
    actionName,
    action: action,
    createdAt: createdAt,
    url: request.reason.url,
    summary: request.reason.body,
    isVoteRequest: hasVoteRequest,
    proposal: buildProposal(request.action),
  } as ProposalDetails;

  const votingInformation: ProposalVotingInformation = {
    requester: request.requester,
    requesterIsYou: request.requester === svPartyId,
    votingCloses: dayjs(request.voteBefore).format(dateTimeFormatISO),
    voteTakesEffect: dayjs(request.targetEffectiveAt).format(dateTimeFormatISO),
    status: hasVoteRequest ? 'In Progress' : getVoteResultStatus(voteResult?.outcome),
  };

  const allVotes = request.votes.entriesArray().map(e => e[1]);

  const votes = allSvs.map(sv => {
    const vote = allVotes.find(v => v.sv === sv);
    return {
      sv: sv,
      isYou: sv === svPartyId,
      vote: !vote ? 'no-vote' : vote.accept ? 'accepted' : 'rejected',
      reason: !vote
        ? undefined
        : {
            url: vote.reason.url,
            body: vote.reason.body,
          },
    } as ProposalVote;
  });

  return (
    <>
      <div>
        VoteRequestDetails for {contractId} <br /> {voteRequest?.payload.action.tag}
      </div>
      <ProposalDetailsContent
        contractId={ccid as ContractId<VoteRequest>}
        proposalDetails={proposalDetails}
        votingInformation={votingInformation}
        votes={votes}
      />
    </>
  );
};
