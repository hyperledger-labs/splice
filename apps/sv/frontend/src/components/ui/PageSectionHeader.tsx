// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Badge, Box, Typography } from '@mui/material';

interface PageSectionHeaderProps {
  title: string;
  badgeCount?: number;
  badgeColor?: 'warning' | 'neutral';
  'data-testid': string;
}

const PageSectionHeader: React.FC<PageSectionHeaderProps> = ({
  title,
  badgeCount,
  badgeColor = 'neutral',
  'data-testid': testId,
}) => (
  <Box sx={{ display: 'flex', alignItems: 'center', mb: 3 }}>
    <Typography variant="h3" fontSize={18} data-testid={`${testId}-title`} id={`${testId}-title`}>
      {title}
    </Typography>
    <Badge
      badgeContent={badgeCount}
      max={Number.MAX_SAFE_INTEGER}
      color={badgeColor}
      sx={{
        ml: 1,
        '& .MuiBadge-badge': {
          position: 'static',
          transform: 'none',
        },
        '& .MuiBadge-invisible': { display: 'none' },
      }}
      id={`${testId}-badge-count`}
      data-testid={`${testId}-badge-count`}
    />
  </Box>
);

export default PageSectionHeader;
