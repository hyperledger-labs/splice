// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { VoteRequest } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { ContractId } from '@daml/types';
import { East } from '@mui/icons-material';
import { Alert, Box, Stack, Typography } from '@mui/material';
import { Link as RouterLink } from 'react-router';
import { CopyableIdentifier, PageSectionHeader } from '../../components/ui';
import {
  CREATE_PROPOSAL_LABEL_PROPOSAL_TYPE,
  VOTE_PROPOSAL_CONTRACT_ID_LABEL,
} from '../../utils/constants';
import React from 'react';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import { formatDatetimeWithOffset } from '../../utils/dateFormat';

dayjs.extend(relativeTime);

export interface ActionRequiredData {
  contractId: ContractId<VoteRequest>;
  actionName: string;
  description: string;
  votingCloses: string;
  createdAt: string;
  requester: string;
}

export interface ActionRequiredProps {
  actionRequiredRequests: ActionRequiredData[];
  noDataMessage?: string;
}

const DEFAULT_NO_DATA_MESSAGE = 'No Action Required items available';

export const ActionRequiredSection: React.FC<ActionRequiredProps> = ({
  actionRequiredRequests,
  noDataMessage = DEFAULT_NO_DATA_MESSAGE,
}) => {
  // Sort by voting closes date ascending (closest deadline first)
  const sortedRequests = actionRequiredRequests.toSorted((a, b) =>
    dayjs(a.votingCloses).isBefore(dayjs(b.votingCloses)) ? -1 : 1
  );

  return (
    <Box sx={{ mb: 4 }} data-testid="action-required-section">
      <PageSectionHeader
        title="Action Required"
        badgeCount={sortedRequests.length}
        badgeColor="warning"
        data-testid="action-required"
      />

      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 3, mb: 3 }}>
        {sortedRequests.length === 0 ? (
          <Alert severity="info" data-testid={'action-required-section-no-items'}>
            {noDataMessage}
          </Alert>
        ) : (
          sortedRequests.map((ar, index) => (
            <ActionCard
              key={index}
              action={ar.actionName}
              description={ar.description}
              createdAt={ar.createdAt}
              contractId={ar.contractId}
              votingEnds={ar.votingCloses}
              requester={ar.requester}
            />
          ))
        )}
      </Box>
    </Box>
  );
};

interface ActionCardProps {
  action: string;
  description: string;
  createdAt: string;
  contractId: ContractId<VoteRequest>;
  votingEnds: string;
  requester: string;
}

const actionRequiredGridTemplate =
  'minmax(0, 0.85fr) minmax(0, 1fr) minmax(0, 1.15fr) minmax(0, 0.75fr) minmax(0, 0.85fr) 270px auto';

const ActionCard = (props: ActionCardProps) => {
  const { action, description, createdAt, contractId, votingEnds, requester } = props;
  const remainingTime = dayjs(votingEnds).fromNow(true);

  return (
    <RouterLink
      to={`/governance/proposals/${contractId}`}
      style={{ textDecoration: 'none' }}
      data-testid="action-required-card-link"
    >
      <Box
        sx={{
          bgcolor: 'colors.neutral.10',
          borderRadius: '4px',
          display: 'grid',
          gridTemplateColumns: actionRequiredGridTemplate,
          alignItems: 'center',
          '&:hover': { backgroundColor: '#363636' },
        }}
        className="action-required-card"
        data-testid="action-required-card"
      >
        <ActionCardSegment
          title={CREATE_PROPOSAL_LABEL_PROPOSAL_TYPE}
          content={action}
          data-testid="action-required-action"
        />
        <ActionCardSegment
          title="DESCRIPTION"
          content={
            <Typography
              variant="body1"
              color="text.light"
              fontWeight="medium"
              fontSize={14}
              lineHeight={1.4}
              sx={{
                display: '-webkit-box',
                WebkitLineClamp: 2,
                WebkitBoxOrient: 'vertical',
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                width: '100%',
              }}
              data-testid="action-required-description-content"
            >
              {description}
            </Typography>
          }
          data-testid="action-required-description"
        />
        <ActionCardSegment
          title={VOTE_PROPOSAL_CONTRACT_ID_LABEL}
          content={
            <CopyableIdentifier
              value={contractId}
              size="small"
              data-testid="action-required-contract-id"
            />
          }
          data-testid="action-required-contract-id-segment"
        />
        <ActionCardSegment
          title="REMAINING TIME"
          content={remainingTime}
          data-testid="action-required-voting-closes"
        />
        <ActionCardSegment
          title="VOTE CREATED"
          content={formatDatetimeWithOffset(createdAt)}
          data-testid="action-required-created-at"
        />
        <ActionCardSegment
          title="SUBMITTED BY"
          content={
            <CopyableIdentifier
              value={requester}
              size="small"
              data-testid="action-required-submitted-by-identifier"
            />
          }
          data-testid="action-required-submitted-by"
        />
        <Stack
          direction="row"
          alignItems="center"
          gap={1}
          sx={{ flexShrink: 0, justifySelf: 'end', py: '15px', px: '16px' }}
          data-testid="action-required-view-details"
        >
          <Typography fontWeight={500} color="text.light" whiteSpace="nowrap">
            View Details
          </Typography>
          <East fontSize="small" color="secondary" />
        </Stack>
      </Box>
    </RouterLink>
  );
};

interface ActionCardSegmentProps {
  title: string;
  content: React.ReactNode;
  'data-testid': string;
}

const ActionCardSegment: React.FC<ActionCardSegmentProps> = ({
  title,
  content,
  'data-testid': testId,
}) => (
  <Stack
    sx={{
      py: '15px',
      px: '16px',
      gap: '4px',
      alignItems: 'flex-start',
      minWidth: 0,
      overflow: 'hidden',
      width: '100%',
    }}
    data-testid={testId}
  >
    <Typography
      fontSize={12}
      lineHeight="22px"
      fontWeight={600}
      variant="subtitle2"
      color="colors.neutral.80"
      sx={{ textTransform: 'uppercase' }}
      data-testid={`${testId}-title`}
    >
      {title}
    </Typography>
    {typeof content === 'string' ? (
      <Typography
        variant="body1"
        color="text.light"
        fontWeight="medium"
        fontSize={14}
        lineHeight="26px"
        data-testid={`${testId}-content`}
      >
        {content}
      </Typography>
    ) : (
      <Box sx={{ width: '100%', minWidth: 0, overflow: 'hidden' }}>{content}</Box>
    )}
  </Stack>
);
