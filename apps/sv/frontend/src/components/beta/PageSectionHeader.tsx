// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Badge, Box, Typography } from '@mui/material';

interface PageSectionHeaderProps {
  title: string;
  badgeCount?: number;
  'data-testid': string;
}

const PageSectionHeader: React.FC<PageSectionHeaderProps> = ({
  title,
  badgeCount,
  'data-testid': testId,
}) => (
  <Box sx={{ display: 'flex', alignItems: 'center', mb: 3 }}>
    <Typography variant="h3" fontFamily="lato" fontSize={18} data-testid={`${testId}-title`}>
      {title}
    </Typography>
    <Badge
      badgeContent={badgeCount}
      color="error"
      sx={{ ml: 2 }}
      id={`${testId}-badge-count`}
      data-testid={`${testId}-badge-count`}
    />
  </Box>
);

export default PageSectionHeader;
