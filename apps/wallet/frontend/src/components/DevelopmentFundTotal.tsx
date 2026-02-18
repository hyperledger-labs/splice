// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';
import { Card, CardContent, Stack, Typography, CircularProgress } from '@mui/material';
import { useDevelopmentFund } from '../hooks/useDevelopmentFund';

const DevelopmentFundTotal: React.FC = () => {
  const { unclaimedTotal, isLoadingUnclaimedTotal } = useDevelopmentFund();

  return (
    <Card variant="outlined">
      <CardContent>
        <Stack spacing={1}>
          <Typography variant="h5">Development Fund Total</Typography>
          <Typography variant="h4" fontWeight="bold" data-testid="unclaimed-total-amount">
            {isLoadingUnclaimedTotal ? (
              <CircularProgress size={24} />
            ) : (
              `${unclaimedTotal.toFixed(4)} CC`
            )}
          </Typography>
        </Stack>
      </CardContent>
    </Card>
  );
};

export default DevelopmentFundTotal;
