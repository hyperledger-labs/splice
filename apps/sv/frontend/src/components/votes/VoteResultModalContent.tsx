// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';

import { CardContent, Stack, Typography } from '@mui/material';

import { ActionRequiringConfirmation } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';

import ActionView from './actions/views/ActionView';

interface VoteRequestModalProps {
  action?: ActionRequiringConfirmation;
}

export const VoteResultModalContent: React.FC<VoteRequestModalProps> = ({ action }) => {
  return (
    <>
      <CardContent sx={{ paddingX: '64px' }}>
        <Stack direction="column" mb={4} spacing={1}>
          <Typography variant="h5">Action</Typography>
          <ActionView action={action!} />
        </Stack>
      </CardContent>
    </>
  );
};
