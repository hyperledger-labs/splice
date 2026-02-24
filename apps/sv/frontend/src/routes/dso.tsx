// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import DsoView from '../components/Dso';
import { Box } from '@mui/material';

const Dso: React.FC = () => {
  return (
    <Box sx={{ p: 4 }}>
      <DsoView />
    </Box>
  );
};

export default Dso;
