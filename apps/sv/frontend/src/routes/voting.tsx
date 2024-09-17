// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';

import { Box } from '@mui/material';

import VoteRequest from '../components/votes/VoteRequest';

const Voting: React.FC = () => {
  return (
    <Box>
      <VoteRequest />
    </Box>
  );
};

export default Voting;
