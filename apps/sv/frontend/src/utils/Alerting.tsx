import React from 'react';

import { Alert, AlertColor } from '@mui/material';

export interface AlertState {
  severity?: AlertColor;
  message?: string;
}
export const Alerting: React.FC<{
  alertState: AlertState;
}> = ({ alertState }) => {
  if (!alertState.severity && !alertState.message) {
    return <React.Fragment />;
  } else {
    return (
      <Alert severity={alertState.severity} id={'alerting-datetime-mismatch'}>
        {alertState.message}
      </Alert>
    );
  }
};
