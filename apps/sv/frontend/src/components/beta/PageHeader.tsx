// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Box, Typography } from '@mui/material';
import React from 'react';

interface PageHeaderProps {
  title: string;
  actionElement?: React.ReactNode;
  'data-testid'?: string;
}

const PageHeader: React.FC<PageHeaderProps> = ({ title, actionElement, 'data-testid': testId }) => (
  <Box
    sx={{ mb: 7, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}
    data-testid={testId}
  >
    <Typography
      variant="h2"
      lineHeight={1}
      fontSize={40}
      fontFamily="termina"
      data-testid={`${testId}-title`}
    >
      {title}
    </Typography>
    {actionElement ?? null}
  </Box>
);

export default PageHeader;
