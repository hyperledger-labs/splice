// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import React from 'react';
import { useParams } from 'react-router';
import { ContractId } from '@daml/types';
import { VoteRequest } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import {
  ProposalDetails,
  ProposalVote,
  ProposalVotingInformation,
  SupportedActionTag,
  UpdateSvRewardWeightProposal,
} from '../utils/types';
import { Box, Typography } from '@mui/material';
import { useSvConfig } from '../utils';
import {
  actionTagToTitle,
  buildProposal,
  formatBasisPoints,
  getActionValue,
  getRequesterPartyId,
  getVoteResultStatus,
} from '../utils/governance';
import { useDsoInfos } from '../contexts/SvContext';
import { useVoteRequestResultByCid } from '../hooks/useVoteRequestResultByCid';
import { usePreviousSvRewardWeight } from '../hooks/usePreviousSvRewardWeight';
import { Loading } from '@canton-network/splice-common-frontend';
import { ProposalDetailsContent } from '../components/governance/ProposalDetailsContent';

export const VoteRequestDetails: React.FC = () => {
  const { contractId } = useParams();
  const ccid = contractId || '';

  const svConfig = useSvConfig();
  const amuletName = svConfig.spliceInstanceNames.amuletName;

  const dsoInfosQuery = useDsoInfos();

  const { hasVoteRequest, hasVoteResult, voteRequest, voteResult, isPending } =
    useVoteRequestResultByCid(contractId as ContractId<VoteRequest>);

  const request = hasVoteRequest
    ? voteRequest?.payload
    : hasVoteResult
      ? voteResult?.request
      : undefined;

  const rewardWeightAction =
    request?.action.tag === 'ARC_DsoRules' &&
    request.action.value.dsoAction.tag === 'SRARC_UpdateSvRewardWeight'
      ? request.action.value.dsoAction.value
      : undefined;
  const currentEffectiveAt =
    voteResult?.outcome.tag === 'VRO_Accepted' ? voteResult.outcome.value.effectiveAt : undefined;
  const { weight: previousRewardWeight, isPending: isPreviousRewardWeightPending } =
    usePreviousSvRewardWeight(
      currentEffectiveAt ? rewardWeightAction?.svParty : undefined,
      currentEffectiveAt
    );

  if (dsoInfosQuery.isPending || isPending) {
    return <Loading />;
  }

  if (!request) {
    return (
      <Typography variant="body1">
        Error, something went wrong. Unable to find the proposal with Contract ID {contractId}.
      </Typography>
    );
  }

  const svPartyId = dsoInfosQuery.data?.svPartyId || '';
  const svs = dsoInfosQuery.data?.dsoRules.payload.svs;
  const allSvs = svs?.entriesArray().map(e => e[0]) || [];
  const amuletOrDsoAction = getActionValue(request.action);

  // check that amuletOrDsoAction is a supported action
  if (
    !amuletOrDsoAction ||
    Object.keys(actionTagToTitle(amuletName)).indexOf(amuletOrDsoAction.tag) === -1
  ) {
    return (
      <Typography variant="body1">
        Error, something went wrong. Unsupported Action: {amuletOrDsoAction?.tag}
      </Typography>
    );
  }

  const action = amuletOrDsoAction.tag as SupportedActionTag;
  const actionName = actionTagToTitle(amuletName)[action];
  const createdAt = voteRequest?.createdAt;

  const proposalDetails: ProposalDetails = {
    actionName,
    action: action,
    createdAt,
    url: request.reason.url,
    summary: request.reason.body,
    isVoteRequest: hasVoteRequest,
    proposal: buildProposal(request.action, dsoInfosQuery.data),
  } as ProposalDetails;

  if (
    action === 'SRARC_UpdateSvRewardWeight' &&
    currentEffectiveAt &&
    !isPreviousRewardWeightPending
  ) {
    (proposalDetails.proposal as UpdateSvRewardWeightProposal).currentWeight =
      previousRewardWeight !== undefined ? formatBasisPoints(previousRewardWeight) : '';
  }

  // For closed votes the outcome carries the actual effective time. Old vote
  // requests (created before targetEffectiveAt existed) decode with
  // targetEffectiveAt = None, so the request alone can't tell "effective at
  // threshold" apart from "effective at expiry".
  const voteTakesEffect = hasVoteRequest
    ? request.targetEffectiveAt
      ? request.targetEffectiveAt
      : 'Threshold'
    : voteResult?.outcome.tag === 'VRO_Accepted'
      ? voteResult.outcome.value.effectiveAt
      : voteResult?.completedAt
        ? voteResult.completedAt
        : '';

  const requesterPartyId = getRequesterPartyId(request.requester, svs);
  const votingInformation: ProposalVotingInformation = {
    requester: requesterPartyId,
    requesterIsYou: requesterPartyId === svPartyId,
    votingThresholdDeadline: request.voteBefore,
    voteTakesEffect,
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
    <Box sx={{ p: 4 }}>
      <ProposalDetailsContent
        currentSvPartyId={svPartyId}
        contractId={ccid as ContractId<VoteRequest>}
        proposalDetails={proposalDetails}
        votingInformation={votingInformation}
        votes={votes}
      />
    </Box>
  );
};
