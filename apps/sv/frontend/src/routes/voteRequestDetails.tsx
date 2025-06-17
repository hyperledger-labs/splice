// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import React from 'react';
import { useParams } from 'react-router-dom';
import { ContractId } from '@daml/types';
import {
  ActionRequiringConfirmation,
  VoteRequest,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import {
  AmuletRulesConfigProposal,
  DsoRulesConfigProposal,
  FeatureAppProposal,
  OffBoardMemberProposal,
  ProposalDetails,
  ProposalVote,
  ProposalVotingInformation,
  UnfeatureAppProposal,
  UpdateSvRewardWeightProposal,
  VoteRequestDetailsContent,
} from '../components/governance/VoteRequestDetailsContent';
import { Typography } from '@mui/material';
import { SupportedActionTag } from './governance';
import { useSvConfig } from '../utils';
import { actionTagToTitle, getVoteResultStatus } from '../utils/governance';
import { useDsoInfos } from '../contexts/SvContext';
import dayjs from 'dayjs';
import { dateTimeFormatISO } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useVoteRequestResultByCid } from '../hooks/useVoteRequestResultByCid';
import { Loading } from '@lfdecentralizedtrust/splice-common-frontend';
import { buildDsoConfigChanges } from '../utils/buildDsoConfigChanges';
import { buildAmuletConfigChanges } from '../utils/buildAmuletConfigChanges';

const buildProposal = (action: ActionRequiringConfirmation) => {
  if (action.tag === 'ARC_DsoRules') {
    const dsoAction = action.value.dsoAction;
    switch (dsoAction.tag) {
      case 'SRARC_OffboardSv':
        return {
          memberToOffboard: dsoAction.value.sv,
        } as OffBoardMemberProposal;
      case 'SRARC_UpdateSvRewardWeight':
        return {
          svToUpdate: dsoAction.value.svParty,
          weightChange: {
            fieldName: 'Weight',
            currentValue: dsoAction.value.newRewardWeight || '0000', //TODO: Weight change has no old value. Change this to just show new value
            newValue: dsoAction.value.newRewardWeight || '0000',
          },
        } as UpdateSvRewardWeightProposal;
      case 'SRARC_GrantFeaturedAppRight':
        return {
          provider: dsoAction.value.provider,
        } as FeatureAppProposal;
      case 'SRARC_RevokeFeaturedAppRight':
        return {
          rightContractId: dsoAction.value.rightCid,
        } as UnfeatureAppProposal;
      case 'SRARC_SetConfig':
        return {
          configChanges: buildDsoConfigChanges(
            dsoAction.value.baseConfig,
            dsoAction.value.newConfig
          ),
        } as DsoRulesConfigProposal;
      default:
        return {};
    }
  } else if (action.tag === 'ARC_AmuletRules') {
    const amuletAction = action.value.amuletRulesAction;
    switch (amuletAction.tag) {
      case 'CRARC_SetConfig':
        return {
          configChanges: buildAmuletConfigChanges(
            amuletAction.value.baseConfig,
            amuletAction.value.newConfig
          ),
        } as AmuletRulesConfigProposal;
      default:
        return {};
    }
  }
};

const getActionValue = (a: ActionRequiringConfirmation) => {
  if (!a) return undefined;

  switch (a.tag) {
    case 'ARC_AmuletRules':
      return a.value.amuletRulesAction;
    case 'ARC_DsoRules':
      return a.value.dsoAction;
    default:
      return undefined;
  }
};

export const VoteRequestDetails: React.FC = () => {
  const { contractId } = useParams();
  const ccid = contractId || '';

  const svConfig = useSvConfig();
  const amuletName = svConfig.spliceInstanceNames.amuletName;

  const dsoInfosQuery = useDsoInfos();

  const ggg = useVoteRequestResultByCid(contractId as ContractId<VoteRequest>);
  console.log('ggg', ggg);

  if (dsoInfosQuery.isPending) {
    return <Loading />;
  }

  // if (!ggg.isComplete) {
  //   return <Typography variant="body1">Error, something went wrong.</Typography>;
  // }

  const voteRequest = ggg.voteRequest;
  const voteResult = ggg.voteResult;
  const request = ggg.hasVoteRequest ? voteRequest?.payload : voteResult?.request;

  if (!request) {
    return (
      <Typography variant="body1">
        Error, something went wrong. Unable to find the vote request with Contract ID {contractId}.
      </Typography>
    );
  }

  console.log('yaya request', request);

  const svPartyId = dsoInfosQuery.data?.svPartyId || '';
  const allSvs = dsoInfosQuery.data?.dsoRules.payload.svs.entriesArray().map(e => e[0]) || [];
  console.log(
    'yaya dsoInfosQuery.data.dsoRules.payload.svs',
    dsoInfosQuery.data?.dsoRules.payload.svs
  );
  console.log('yaya allSvs', allSvs);

  const amuletOrDsoAction = getActionValue(request.action);
  console.log('yaya aa', amuletOrDsoAction);

  // check that amuletOrDsoAction is a supported action
  if (
    !amuletOrDsoAction ||
    Object.keys(actionTagToTitle(amuletName)).indexOf(amuletOrDsoAction.tag) === -1
  ) {
    return <Typography variant="body1">Error, something went wrong. Unsupported action</Typography>;
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
    isVoteRequest: ggg.hasVoteRequest,
    proposal: buildProposal(request.action),
  } as ProposalDetails;

  const votingInformation: ProposalVotingInformation = {
    requester: request.requester,
    requesterIsYou: request.requester === svPartyId,
    votingCloses: dayjs(request.voteBefore).format(dateTimeFormatISO),
    voteTakesEffect: dayjs(request.targetEffectiveAt).format(dateTimeFormatISO),
    status: ggg.hasVoteRequest ? 'In Progress' : getVoteResultStatus(voteResult?.outcome),
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
      <VoteRequestDetailsContent
        contractId={ccid as ContractId<VoteRequest>}
        proposalDetails={proposalDetails}
        votingInformation={votingInformation}
        votes={votes}
      />
    </>
  );
};
