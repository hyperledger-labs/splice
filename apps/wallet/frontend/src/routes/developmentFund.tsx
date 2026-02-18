// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';
import { Alert, Box, Stack } from '@mui/material';
import { Loading } from '@lfdecentralizedtrust/splice-common-frontend';
import DevelopmentFundTotal from '../components/DevelopmentFundTotal';
import DevelopmentFundAllocation from '../components/DevelopmentFundAllocation';
import DevelopmentFundCouponList from '../components/DevelopmentFundCouponList';
import { useDevelopmentFund } from '../hooks/useDevelopmentFund';

const DevelopmentFund: React.FC = () => {
  const { isFundManager, isLoading } = useDevelopmentFund();

  if (isLoading) {
    return <Loading />;
  }

  return (
    <Box marginTop={4}>
      <Stack spacing={4}>
        {!isFundManager && (
          <Alert severity="info">
            Your party is not the development fund manager designated by the CF foundation. If your
            party was formerly the development fund manager you can use this page to manage your
            active development fund allocations and review the history of your past allocations.
            Otherwise, you likely want to ignore the content on this page.
          </Alert>
        )}
        <DevelopmentFundTotal />
        <DevelopmentFundAllocation />
        <DevelopmentFundCouponList />
      </Stack>
    </Box>
  );
};

export default DevelopmentFund;
