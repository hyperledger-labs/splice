// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Box, Divider, TextField as MuiTextField, Typography } from '@mui/material';
import { useFieldContext } from '../../hooks/formContext';
import { ConfigChange } from '../../utils/types';

export interface ConfigFieldProps {
  configChange: ConfigChange;
}

export type ConfigFieldState = {
  fieldName: string;
  value: string;
};

export const ConfigField: React.FC<ConfigFieldProps> = props => {
  const { configChange } = props;
  const field = useFieldContext<ConfigFieldState>();

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
            variant="outlined"
            size="small"
            // We choose empty string to represent fields that could be undefined because their values have not been set.
            value={field.state.value?.value ? field.state.value.value : ''}
            onBlur={field.handleBlur}
            onChange={e =>
              field.handleChange({ fieldName: configChange.fieldName, value: e.target.value })
            }
            color={field.state.meta.isDefaultValue ? 'primary' : 'secondary'}
            focused={!field.state.meta.isDefaultValue}
            autoComplete="off"
            inputProps={{
              sx: {
                textAlign: 'right',
              },
              'data-testid': `config-field-${configChange.fieldName}`,
            }}
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
        </Box>
      </Box>
      <Divider />
    </>
  );
};
