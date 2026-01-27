// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';
import { Outlet } from 'react-router';

import { Box } from '@mui/material';

import PaymentHeader from '../components/PaymentHeader';

export const Confirmation: React.FC = () => {
  return (
    <Box display="flex" flexDirection="column" minHeight="100vh" id="confirm-payment">
      <PaymentHeader />
      <Box bgcolor="colors.neutral.15" flex={1}>
        <Outlet />
      </Box>
    </Box>
  );
};

export default Confirmation;
