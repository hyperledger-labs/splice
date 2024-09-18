// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React, { useEffect } from 'react';

import { ErrorOutline } from '@mui/icons-material';
import { Box, Button, Typography } from '@mui/material';

import { theme } from '../theme';

type ErrorDisplayComponent = 'div' | 'tr';

interface ErrorProps {
  renderAs?: ErrorDisplayComponent;
  message: string;
  userAction?: string;
  details?: string;
  retryFn?: () => void;
}

const ErrorDisplay: React.FC<ErrorProps> = props => {
  useEffect(
    () => console.debug('Displaying error ', props.message, props.userAction, props.details),
    [props.message, props.userAction, props.details]
  );
  return (
    <Box
      component={props.renderAs ?? 'div'}
      sx={{
        width: '100%',
        height: '100%',
        display: 'flex',
        flexDirection: 'column',
        justifyContent: 'center',
        alignItems: 'center',
      }}
    >
      <ErrorOutline id="error-icon" color="error" sx={{ width: '40px', height: '40px' }} />
      <Typography
        className="error-display-message"
        variant="subtitle1"
        color={theme.palette.error.main}
        gutterBottom
      >
        {props.message}
      </Typography>
      {props.userAction && (
        <Typography
          className="error-display-useraction"
          variant="body1"
          color={theme.palette.error.main}
          gutterBottom
        >
          {props.userAction}
        </Typography>
      )}
      {props.details && (
        <Typography
          className="error-display-details"
          variant="body2"
          color={theme.palette.error.main}
          gutterBottom
        >
          {props.details}
        </Typography>
      )}
      {props.retryFn && (
        <Button className="error-display-button" variant="outlined" onClick={props.retryFn}>
          Try Again
        </Button>
      )}
    </Box>
  );
};

export default ErrorDisplay;
