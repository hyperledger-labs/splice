// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Box, Divider, TextField as MuiTextField, Typography } from '@mui/material';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import { useFieldContext } from '../../hooks/formContext';
import type { ConfigChange, PendingConfigFieldInfo } from '../../utils/types';

dayjs.extend(relativeTime);

export interface ConfigFieldProps {
  configChange: ConfigChange;
  pendingFieldInfo?: PendingConfigFieldInfo;
}

export type ConfigFieldState = {
  fieldName: string;
  value: string;
};

export const ConfigField: React.FC<ConfigFieldProps> = props => {
  const { configChange, pendingFieldInfo } = props;
  const field = useFieldContext<ConfigFieldState>();
  const textFieldProps = {
    variant: 'outlined' as const,
    size: 'small' as const,
    color: field.state.meta.isDefaultValue ? ('primary' as const) : ('secondary' as const),
    focused: !field.state.meta.isDefaultValue,
    autoComplete: 'off' as const,
    inputProps: {
      sx: { textAlign: 'right' },
      'data-testid': `config-field-${configChange.fieldName}`,
    },
    disabled: pendingFieldInfo !== undefined,
  };

  return (
    <>
      <Box
        sx={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
        }}
      >
        <Typography variant="body1" data-testid={`config-label-${configChange.fieldName}`}>
          {configChange.label}
        </Typography>

        <Box sx={{ width: 250 }}>
          <MuiTextField
            {...textFieldProps}
            // We choose empty string to represent fields that could be undefined because their values have not been set.
            value={field.state.value?.value || ''}
            onBlur={field.handleBlur}
            onChange={e =>
              field.handleChange({
                fieldName: configChange.fieldName,
                value: e.target.value,
              })
            }
          />

          {!field.state.meta.isDefaultValue && (
            <Typography
              variant="caption"
              color="text.secondary"
              sx={{ mt: 0.5, display: 'block', textAlign: 'center' }}
              data-testid={`config-current-value-${configChange.fieldName}`}
            >
              Current Configuration: {configChange.currentValue}
            </Typography>
          )}

          {pendingFieldInfo && <PendingConfigDisplay pendingFieldInfo={pendingFieldInfo} />}
        </Box>
      </Box>
      <Divider />
    </>
  );
};

interface PendingConfigDisplayProps {
  pendingFieldInfo: PendingConfigFieldInfo;
}

export const PendingConfigDisplay: React.FC<PendingConfigDisplayProps> = ({ pendingFieldInfo }) => {
  const atThreshold = pendingFieldInfo.effectiveDate === 'Threshold';
  return (
    <Typography
      variant="caption"
      color="text.secondary"
      sx={{ mt: 0.5, display: 'block', textAlign: 'center' }}
      data-testid={`config-pending-value-${pendingFieldInfo.fieldName}`}
    >
      Pending Configuration: <strong>{pendingFieldInfo.pendingValue}</strong> <br />
      This proposal will go into effect{' '}
      <strong>
        {atThreshold ? 'at Threshold' : dayjs(pendingFieldInfo.effectiveDate).fromNow()}
      </strong>
    </Typography>
  );
};
