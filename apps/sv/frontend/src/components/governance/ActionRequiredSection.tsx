// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { VoteRequest } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { ContractId } from '@daml/types';
import { East } from '@mui/icons-material';
import { Alert, Box, Grid, Stack, Typography } from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';
import { MemberIdentifier, PageSectionHeader } from '../../components/beta';
import React from 'react';

export interface ActionRequiredData {
  contractId: ContractId<VoteRequest>;
  actionName: string;
  votingCloses: string;
  createdAt: string;
  requester: string;
  isYou?: boolean;
}

export interface ActionRequiredProps {
  actionRequiredRequests: ActionRequiredData[];
}

export const ActionRequiredSection: React.FC<ActionRequiredProps> = (
  props: ActionRequiredProps
) => {
  const { actionRequiredRequests } = props;

  return (
    <Box sx={{ mb: 4 }} data-testid="action-required-section">
      <PageSectionHeader
        title="Action Required"
        badgeCount={actionRequiredRequests.length}
        data-testid="action-required"
      />

      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 3, mb: 3 }}>
        {actionRequiredRequests.length === 0 ? (
          <Alert severity="info" data-testid={'action-required-section-no-items'}>
            No Action Required items available
          </Alert>
        ) : (
          actionRequiredRequests.map((ar, index) => (
            <ActionCard
              key={index}
              action={ar.actionName}
              createdAt={ar.createdAt}
              contractId={ar.contractId}
              votingEnds={ar.votingCloses}
              requester={ar.requester}
              isYou={ar.isYou}
            />
          ))
        )}
      </Box>
    </Box>
  );
};

interface ActionCardProps {
  action: string;
  contractId: ContractId<VoteRequest>;
  createdAt: string;
  votingEnds: string;
  requester: string;
  isYou?: boolean;
}

const ActionCard = (props: ActionCardProps) => {
  const { action, createdAt, contractId, votingEnds, requester, isYou } = props;

  return (
    <RouterLink to={`/governance-beta/proposals/${contractId}`} style={{ textDecoration: 'none' }}>
      <Box
        sx={{
          bgcolor: 'background.paper',
          p: 2,
          borderRadius: '4px',
          '&:hover': { backgroundColor: '#363636' },
        }}
        className="action-required-card"
        data-testid="action-required-card"
      >
        <Grid flexGrow={1} container spacing={1}>
          <Grid size={2}>
            <ActionCardSegment
              title="ACTION"
              content={action}
              data-testid="action-required-action"
            />
          </Grid>
          <Grid size={2}>
            <ActionCardSegment
              title="CREATED AT"
              content={createdAt}
              data-testid="action-required-created-at"
            />
          </Grid>
          <Grid size={2}>
            <ActionCardSegment
              title="THRESHOLD DEADLINE"
              content={votingEnds}
              data-testid="action-required-voting-closes"
            />
          </Grid>
          <Grid size={4}>
            <Box>
              <ActionCardSegment
                title="REQUESTER"
                content={
                  <MemberIdentifier
                    partyId={requester}
                    isYou={isYou ?? false}
                    data-testid="action-required-requester-identifier"
                  />
                }
                data-testid="action-required-requester"
              />
            </Box>
          </Grid>
          <Grid size={2} display="flex" justifyContent="flex-end" alignItems="center">
            <Stack
              direction="row"
              alignItems="center"
              gap={1}
              data-testid="action-required-view-details"
            >
              <Typography fontWeight={500} color="text.light">
                View Details
              </Typography>
              <East fontSize="small" color="secondary" />
            </Stack>
          </Grid>
        </Grid>
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
  <Stack height="100%" justifyContent="space-between" data-testid={testId}>
    <Typography
      fontSize={12}
      lineHeight={2}
      fontFamily="lato"
      fontWeight={700}
      variant="subtitle2"
      color="text.light"
      gutterBottom
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
        lineHeight={2}
        data-testid={`${testId}-content`}
      >
        {content}
      </Typography>
    ) : (
      content
    )}
  </Stack>
);
