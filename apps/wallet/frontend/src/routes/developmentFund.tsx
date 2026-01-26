// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';
import { Box, Stack } from '@mui/material';
import DevelopmentFundTotal from '../components/DevelopmentFundTotal';
import DevelopmentFundAllocation from '../components/DevelopmentFundAllocation';
import DevelopmentFundCouponList from '../components/DevelopmentFundCouponList';

const DevelopmentFund: React.FC = () => (
  <Box marginTop={4}>
    <Stack spacing={4}>
      <DevelopmentFundTotal />
      <DevelopmentFundAllocation />
      <DevelopmentFundCouponList />
    </Stack>
  </Box>
);
export default DevelopmentFund;
