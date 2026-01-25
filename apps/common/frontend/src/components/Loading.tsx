// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';

import { Box, CircularProgress } from '@mui/material';

type LoadingComponent = 'div' | 'tr';

interface LoadingProps {
  renderAs?: LoadingComponent;
}

const Loading: React.FC<LoadingProps> = props => {
  return (
    <Box
      component={props.renderAs ?? 'div'}
      sx={{
        width: '100%',
        height: '100%',
        display: 'flex',
        justifyContent: 'center',
        alignItems: 'center',
      }}
      data-testid={'loading-spinner'}
    >
      <CircularProgress sx={{ display: 'flex' }} />
    </Box>
  );
};

export default Loading;
