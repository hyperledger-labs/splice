// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { ArrowForward, ContentCopy } from '@mui/icons-material';
import { Badge, Box, Button, Card, Chip, Grid, IconButton, Typography } from '@mui/material';

export interface ActionRequiredData {
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
    <Box sx={{ mb: 4 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', mb: 2 }}>
        <Typography variant="h5" id="action-required-header" data-testid="action-required-header">
          Action Required
        </Typography>
        <Badge
          badgeContent={actionRequiredRequests.length}
          color="error"
          sx={{ ml: 2 }}
          id="action-required-badge-count"
          data-testid="action-required-badge-count"
        />
      </Box>

      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, mb: 3 }}>
        {actionRequiredRequests.map((ar, index) => (
          <ActionCard
            key={index}
            action={ar.actionName}
            createdAt={ar.createdAt}
            votingEnds={ar.votingCloses}
            requester={ar.requester}
            isYou={ar.isYou}
          />
        ))}
      </Box>
    </Box>
  );
};

interface ActionCardProps {
  action: string;
  createdAt: string;
  votingEnds: string;
  requester: string;
  isYou?: boolean;
}

const ActionCard = (props: ActionCardProps) => {
  const { action, createdAt, votingEnds, requester, isYou } = props;

  return (
    <Card
      sx={{ bgcolor: 'background.paper' }}
      className="action-required-card"
      data-testid="action-required-card"
    >
      <Box
        sx={{
          p: 2,
          display: 'flex',
          justifyContent: 'space-between',
        }}
      >
        <Grid container spacing={1}>
          <Grid xs={3}>
            <Box>
              <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                ACTION
              </Typography>
              <Typography variant="body1" fontWeight="medium" data-testid="action-required-action">
                {action}
              </Typography>
            </Box>
          </Grid>
          <Grid xs={2}>
            <Box>
              <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                CREATED AT
              </Typography>
              <Typography
                variant="body1"
                fontWeight="medium"
                data-testid="action-required-created-at"
              >
                {createdAt}
              </Typography>
            </Box>
          </Grid>
          <Grid xs={2}>
            <Box>
              <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                VOTING CLOSES
              </Typography>
              <Typography
                variant="body1"
                fontWeight="medium"
                data-testid="action-required-voting-closes"
              >
                {votingEnds}
              </Typography>
            </Box>
          </Grid>
          <Grid xs={2}>
            <Box>
              <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                REQUESTER
              </Typography>
              <Box sx={{ display: 'flex', alignItems: 'center' }}>
                <Typography
                  variant="body1"
                  fontWeight="medium"
                  data-testid="action-required-requester"
                >
                  {requester}
                </Typography>
                <IconButton onClick={() => navigator.clipboard.writeText(requester)}>
                  <ContentCopy />
                </IconButton>
                {isYou && <Chip label="You" size="small" data-testid="action-required-you" />}
              </Box>
            </Box>
          </Grid>
        </Grid>

        <Button
          endIcon={<ArrowForward />}
          size="small"
          sx={{ alignSelf: { xs: 'flex-end', sm: 'center' } }}
          data-testid="action-required-view-details"
        >
          View Details
        </Button>
      </Box>
    </Card>
  );
};
