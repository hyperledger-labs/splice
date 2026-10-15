// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Stack, Typography, TypographyProps } from '@mui/material';
import CheckCircleOutlineIcon from '@mui/icons-material/CheckCircleOutline';
import CancelOutlinedIcon from '@mui/icons-material/CancelOutlined';
import { YourVoteStatus } from '../../utils/types';

interface VoteStatsProps {
  vote: YourVoteStatus;
  noVoteMessage?: string;
  count?: number;
  typography?: TypographyProps;
  'data-testid': string;
}

const VoteStats: React.FC<VoteStatsProps> = ({
  vote,
  noVoteMessage = 'No Vote',
  count,
  typography,
  'data-testid': testId,
}) => {
  if (vote === 'accepted') {
    return (
      <Stack direction="row" gap="4px" alignItems="center" data-testid={testId}>
        <CheckCircleOutlineIcon
          fontSize="small"
          color="success"
          data-testid={`${testId}-accepted-icon`}
        />
        <Typography {...typography} data-testid={`${testId}-value`}>
          {count !== undefined && `${count} `}Accepted
        </Typography>
      </Stack>
    );
  }

  if (vote === 'rejected') {
    return (
      <Stack direction="row" gap="4px" alignItems="center" data-testid={testId}>
        <CancelOutlinedIcon
          fontSize="small"
          color="error"
          data-testid={`${testId}-rejected-icon`}
        />
        <Typography {...typography} data-testid={`${testId}-value`}>
          {count !== undefined && `${count} `}Rejected
        </Typography>
      </Stack>
    );
  }

  return (
    <Typography {...typography} data-testid={`${testId}-value`}>
      {noVoteMessage}
    </Typography>
  );
};

export default VoteStats;
