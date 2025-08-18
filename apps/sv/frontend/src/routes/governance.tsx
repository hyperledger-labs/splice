// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import { Box, Button, Typography } from '@mui/material';
import { useMemo } from 'react';
import {
  ActionRequiredSection,
  ActionRequiredData,
} from '../components/governance/ActionRequiredSection';
import { Loading, useVotesHooks } from '@lfdecentralizedtrust/splice-common-frontend';
import { dateTimeFormatISO } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import dayjs from 'dayjs';
import { ContractId } from '@daml/types';
import {
  ActionRequiringConfirmation,
  VoteRequest,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { useSvConfig } from '../utils';
import { ProposalListingSection } from '../components/governance/ProposalListingSection';
import {
  actionTagToTitle,
  computeVoteStats,
  computeYourVote,
  getVoteResultStatus,
} from '../utils/governance';
import { SupportedActionTag, ProposalListingData } from '../utils/types';
import { Link as RouterLink } from 'react-router-dom';

function getAction(action: ActionRequiringConfirmation): string {
  switch (action.tag) {
    case 'ARC_AmuletRules':
      return action.value.amuletRulesAction.tag;
    case 'ARC_DsoRules':
      return action.value.dsoAction.tag;
    default:
      return 'Action tag not defined.';
  }
}

const QUERY_LIMIT = 50;

export const Governance: React.FC = () => {
  const svConfig = useSvConfig();
  const amuletName = svConfig.spliceInstanceNames.amuletName;

  const votesHooks = useVotesHooks();
  const dsoInfosQuery = votesHooks.useDsoInfos();
  const listVoteRequestsQuery = votesHooks.useListDsoRulesVoteRequests();
  const voteResultsWithAcceptedQuery = (accepted: boolean) =>
    votesHooks.useListVoteRequestResult(
      QUERY_LIMIT,
      undefined,
      undefined,
      undefined,
      undefined,
      accepted
    );
  const acceptedResultsQuery = voteResultsWithAcceptedQuery(true);
  const notAcceptedResultsQuery = voteResultsWithAcceptedQuery(false);

  const voteRequestIds = listVoteRequestsQuery.data
    ? listVoteRequestsQuery.data.map(v => v.payload.trackingCid || v.contractId)
    : [];
  const votesQuery = votesHooks.useListVotes(voteRequestIds);

  const svPartyId = dsoInfosQuery.data?.svPartyId;
  const alreadyVotedRequestIds: Set<ContractId<VoteRequest>> = useMemo(() => {
    return svPartyId && votesQuery.data
      ? new Set(votesQuery.data.filter(v => v.voter === svPartyId).map(v => v.requestCid))
      : new Set();
  }, [votesQuery.data, svPartyId]);

  if (
    dsoInfosQuery.isPending ||
    listVoteRequestsQuery.isPending ||
    votesQuery.isPending ||
    acceptedResultsQuery.isPending ||
    notAcceptedResultsQuery.isPending
  ) {
    return <Loading />;
  }

  if (
    dsoInfosQuery.isError ||
    listVoteRequestsQuery.isError ||
    votesQuery.isError ||
    acceptedResultsQuery.isError ||
    notAcceptedResultsQuery.isError
  ) {
    return <Typography variant="body1">Error, something went wrong.</Typography>;
  }

  const voteRequests = listVoteRequestsQuery.data;
  const votingThreshold = dsoInfosQuery.data.votingThreshold;

  const actionRequiredRequests = voteRequests
    .filter(v => !alreadyVotedRequestIds.has(v.payload.trackingCid || v.contractId))
    .map(vr => {
      return {
        contractId: vr.payload.trackingCid || vr.contractId,
        actionName:
          actionTagToTitle(amuletName)[getAction(vr.payload.action) as SupportedActionTag],
        votingCloses: dayjs(vr.payload.voteBefore).format(dateTimeFormatISO),
        createdAt: dayjs(vr.createdAt).format(dateTimeFormatISO),
        requester: vr.payload.requester,
        isYou: vr.payload.requester === svPartyId,
      } as ActionRequiredData;
    });

  const inflightRequests = voteRequests
    .filter(v => alreadyVotedRequestIds.has(v.payload.trackingCid || v.contractId))
    .map(v => {
      const effectiveAt = v.payload.targetEffectiveAt
        ? dayjs(v.payload.targetEffectiveAt).format(dateTimeFormatISO)
        : 'Threshold';

      const votes = v.payload.votes.entriesArray().map(e => e[1]);

      return {
        contractId: v.payload.trackingCid || v.contractId,
        actionName: actionTagToTitle(amuletName)[getAction(v.payload.action) as SupportedActionTag],
        votingCloses: dayjs(v.payload.voteBefore).format(dateTimeFormatISO),
        voteTakesEffect: effectiveAt,
        yourVote: computeYourVote(votes, svPartyId),
        status: 'In Progress',
        voteStats: computeVoteStats(votes),
        acceptanceThreshold: votingThreshold,
      } as ProposalListingData;
    });

  const acceptedRequests = acceptedResultsQuery.data.filter(
    vr => vr.outcome.tag === 'VRO_Accepted' && dayjs(vr.outcome.value.effectiveAt).isBefore(dayjs())
  );

  const notAcceptedRequests = notAcceptedResultsQuery.data.filter(
    vr => vr.outcome.tag === 'VRO_Expired' || vr.outcome.tag === 'VRO_Rejected'
  );

  const allRequests = [...acceptedRequests, ...notAcceptedRequests];

  const voteHistory = allRequests
    .map(vr => {
      const votes = vr.request.votes.entriesArray().map(e => e[1]);

      return {
        contractId: vr.request.trackingCid,
        actionName:
          actionTagToTitle(amuletName)[getAction(vr.request.action) as SupportedActionTag],
        votingCloses: dayjs(vr.request.voteBefore).format(dateTimeFormatISO),
        voteTakesEffect:
          (vr.outcome.tag === 'VRO_Accepted' &&
            dayjs(vr.outcome.value.effectiveAt).format(dateTimeFormatISO)) ||
          dayjs(vr.completedAt).format(dateTimeFormatISO),
        yourVote: computeYourVote(votes, svPartyId),
        status: getVoteResultStatus(vr.outcome),
        voteStats: computeVoteStats(votes),
        acceptanceThreshold: votingThreshold,
      } as ProposalListingData;
    })
    .sort((a, b) => (dayjs(a.voteTakesEffect).isAfter(dayjs(b.voteTakesEffect)) ? -1 : 1));

  return (
    <Box sx={{ p: 4 }}>
      <Box sx={{ mb: 8, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Typography variant="h2" gutterBottom data-testid="governance-page-title">
          Governance
        </Typography>
        <Button variant="pill" component={RouterLink} to={`/governance-beta/proposals/create`}>
          Initiate Proposal
        </Button>
      </Box>

      <ActionRequiredSection actionRequiredRequests={actionRequiredRequests} />

      <ProposalListingSection
        sectionTitle="Inflight Votes"
        data={inflightRequests}
        uniqueId="inflight-vote-requests"
        showVoteStats
        showAcceptanceThreshold
      />

      <ProposalListingSection
        sectionTitle="Vote History"
        data={voteHistory}
        uniqueId="vote-history"
        showStatus
      />
    </Box>
  );
};
