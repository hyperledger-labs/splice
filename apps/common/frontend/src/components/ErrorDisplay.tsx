import * as React from 'react';

import { Box, Typography } from '@mui/material';

import { theme } from '../theme';

type ErrorDisplayComponent = 'div' | 'tr';

interface ErrorProps {
  renderAs?: ErrorDisplayComponent;
  message: string;
}

const ErrorDisplay: React.FC<ErrorProps> = props => {
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
    >
      <Typography className="error-display-text" variant="caption" color={theme.palette.error.main}>
        {props.message}
      </Typography>
    </Box>
  );
};

export default ErrorDisplay;
