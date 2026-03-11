// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';
import { Alert, Card, CardContent, Stack, Typography, CircularProgress } from '@mui/material';
import { useDevelopmentFund } from '../hooks/useDevelopmentFund';
import { extractApiErrorMessage } from '@lfdecentralizedtrust/splice-common-frontend';
import { useWalletConfig } from '../utils/config';

const DevelopmentFundTotal: React.FC = () => {
  const config = useWalletConfig();
  const { unclaimedTotal, isLoadingUnclaimedTotal, isUnclaimedTotalError, unclaimedTotalError } =
    useDevelopmentFund();

  if (isUnclaimedTotalError) {
    return (
      <Alert severity="error">
        Error loading development fund total: {extractApiErrorMessage(unclaimedTotalError)}
      </Alert>
    );
  }

  return (
    <Card variant="outlined">
      <CardContent>
        <Stack spacing={1}>
          <Typography variant="h5">Development Fund Total</Typography>
          <Typography variant="h4" fontWeight="bold" id="unclaimed-total-amount">
            {isLoadingUnclaimedTotal ? (
              <CircularProgress size={24} />
            ) : (
              `${unclaimedTotal.toFixed(4)} ${config.spliceInstanceNames.amuletNameAcronym}`
            )}
          </Typography>
        </Stack>
      </CardContent>
    </Card>
  );
};

export default DevelopmentFundTotal;
