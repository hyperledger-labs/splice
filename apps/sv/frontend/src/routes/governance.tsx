// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import { Box, Tab, Tabs, Typography } from '@mui/material';
import { useMemo, useState } from 'react';
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

export type YourVoteStatus = 'accepted' | 'rejected' | 'not-voted';

type SupportedActionTag =
  | 'CRARC_AddFutureAmuletConfigSchedule'
  | 'CRARC_SetConfig'
  | 'SRARC_GrantFeaturedAppRight'
  | 'SRARC_OffboardSv'
  | 'SRARC_RevokeFeaturedAppRight'
  | 'SRARC_SetConfig'
  | 'SRARC_UpdateSvRewardWeight';

const actionTagToTitle: Record<SupportedActionTag, string> = {
  CRARC_AddFutureAmuletConfigSchedule: 'Add Future Amulet Configuration Schedule',
  CRARC_SetConfig: 'Set Amulet Rules Configuration',
  SRARC_GrantFeaturedAppRight: 'Feature Application',
  SRARC_OffboardSv: 'Offboard Member',
  SRARC_RevokeFeaturedAppRight: 'Unfeature Application',
  SRARC_SetConfig: 'Set Dso Rules Configuration',
  SRARC_UpdateSvRewardWeight: 'Update SV Reward Weight',
};

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
  const [tabValue, setTabValue] = useState('voting');

  const votesHooks = useVotesHooks();
  const dsoInfosQuery = votesHooks.useDsoInfos();
  const listVoteRequestsQuery = votesHooks.useListDsoRulesVoteRequests();
  const voteResultsQuery = votesHooks.useListVoteRequestResult(QUERY_LIMIT);

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
    voteResultsQuery.isPending
  ) {
    return <Loading />;
  }

  if (dsoInfosQuery.isError || listVoteRequestsQuery.isError || votesQuery.isError) {
    return <p>Error, something went wrong.</p>;
  }

  const voteRequests = listVoteRequestsQuery.data;

  const actionRequiredRequests = voteRequests
    .filter(v => !alreadyVotedRequestIds.has(v.payload.trackingCid || v.contractId))
    .map(vr => {
      return {
        actionName: actionTagToTitle[getAction(vr.payload.action) as SupportedActionTag],
        votingCloses: dayjs(vr.payload.voteBefore).format(dateTimeFormatISO),
        createdAt: dayjs(vr.createdAt).format(dateTimeFormatISO),
        requester: vr.payload.requester,
        isYou: vr.payload.requester === svPartyId,
      } as ActionRequiredData;
    });

  return (
    <Box sx={{ p: 4 }}>
      <Typography variant="h1" gutterBottom>
        Governance
      </Typography>

      <Box sx={{ borderBottom: 1, borderColor: 'divider', mb: 3 }}>
        <Tabs value={tabValue} onChange={(_, newValue) => setTabValue(newValue)}>
          <Tab label="Voting" value="voting" />
          <Tab label="Initiate Vote" value="initiate-vote" />
        </Tabs>
      </Box>

      <ActionRequiredSection actionRequiredRequests={actionRequiredRequests} />
    </Box>
  );
};
