// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { useWalletClient } from '../contexts/WalletServiceContext';
import { Card, CardContent, Stack, Typography } from '@mui/material';
import { Loading } from '@lfdecentralizedtrust/splice-common-frontend';

const DevelopmentFundTotal: React.FC = () => {
  const { getDevelopmentFundTotal } = useWalletClient();
  const totalQuery = useQuery({
    queryKey: ['developmentFundTotal'],
    queryFn: () => getDevelopmentFundTotal(),
  });

  if (totalQuery.isLoading) {
    return <Loading />;
  }

  if (totalQuery.isError) {
    return (
      <Typography color="error">
        Error loading development fund total: {JSON.stringify(totalQuery.error)}
      </Typography>
    );
  }

  const total = totalQuery.data;

  if (!total) {
    return (
      <Typography color="error">
        Error: Development fund total is not available
      </Typography>
    );
  }

  return (
    <Card variant="outlined">
      <CardContent>
        <Stack spacing={1}>
          <Typography variant="h5">Development Fund Total</Typography>
          <Typography variant="h4" fontWeight="bold">
            {total.toFixed(4)} CC
          </Typography>
        </Stack>
      </CardContent>
    </Card>
  );
};

export default DevelopmentFundTotal;
