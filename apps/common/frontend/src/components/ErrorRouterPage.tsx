// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';
import { Link as RouterLink, useRouteError } from 'react-router';

import { Box, Link, Typography } from '@mui/material';

const ErrorRouterPage: React.FC = () => {
  const error = useRouteError() as { statusText?: string; message?: string };

  return (
    <Box
      sx={{ flex: 1 }}
      display="flex"
      height="100%"
      flexDirection="column"
      justifyContent="space-between"
      alignItems="center"
    >
      <Box display="flex" flexDirection="column" alignItems="center" height="100%" marginTop={8}>
        <Typography variant="h1">Something went wrong!</Typography>

        <Box
          marginTop={4}
          marginBottom={2}
          display="flex"
          flexDirection="column"
          alignItems="center"
        >
          <Typography variant="h5">An unexpected error has occurred.</Typography>
        </Box>

        <Typography variant="body1">{error?.statusText || error?.message}</Typography>

        <Box marginTop={8}>
          <Link component={RouterLink} variant="pill" to="/">
            Return to Home
          </Link>
        </Box>
      </Box>
    </Box>
  );
};

export default ErrorRouterPage;
