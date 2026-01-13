// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import {
  Box,
  TextField as MuiTextField,
  TextFieldProps as MuiTextFieldProps,
  Typography,
} from '@mui/material';
import { useFieldContext } from '../../hooks/formContext';

export interface TextFieldProps {
  id: string;
  title: string;
  subtitle?: string;
  muiTextFieldProps?: MuiTextFieldProps;
}

export const TextField: React.FC<TextFieldProps> = props => {
  const { title, subtitle, id, muiTextFieldProps } = props;
  const field = useFieldContext<string>();
  return (
    <Box>
      <Typography variant="h6" id={`${id}-title`} data-testid={`${id}-title`} gutterBottom>
        {title}
      </Typography>

      <MuiTextField
        fullWidth
        variant="outlined"
        autoComplete="off"
        value={field.state.value}
        onBlur={field.handleBlur}
        error={!field.state.meta.isValid}
        helperText={
          <Typography variant="caption" id={`${id}-error`} data-testid={`${id}-error`}>
            {field.state.meta.errors?.[0]}
          </Typography>
        }
        onChange={e => field.handleChange(e.target.value)}
        inputProps={{ 'data-testid': id }}
        id={id}
        {...muiTextFieldProps}
      />
      {subtitle && (
        <Typography variant="body2" color="text.secondary" data-testid={`${id}-subtitle`} mt={1}>
          {subtitle}
        </Typography>
      )}
    </Box>
  );
};
