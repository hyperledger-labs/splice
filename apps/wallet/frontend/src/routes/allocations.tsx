// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';
import CreateAllocation from '../components/CreateAllocation';
import { Box } from '@mui/material';
import ListAllocationRequests from '../components/ListAllocationRequests';
import ListAllocations from '../components/ListAllocations';

const Allocations: React.FC = () => (
  <Box marginTop={4}>
    <ListAllocationRequests />
    <ListAllocations />
    <CreateAllocation />
  </Box>
);
export default Allocations;
