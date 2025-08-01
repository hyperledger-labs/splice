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
  muiTextFieldProps?: MuiTextFieldProps;
}

export const TextField: React.FC<TextFieldProps> = props => {
  const { title, id, muiTextFieldProps } = props;
  const field = useFieldContext<string>();
  return (
    <Box>
      <Typography variant="h6" gutterBottom>
        {title}
      </Typography>

      <MuiTextField
        fullWidth
        variant="outlined"
        autoComplete="off"
        value={field.state.value}
        onBlur={field.handleBlur}
        error={!field.state.meta.isValid}
        helperText={field.state.meta.errors?.[0]}
        onChange={e => field.handleChange(e.target.value)}
        inputProps={{ 'data-testid': id }}
        {...muiTextFieldProps}
      />
    </Box>
  );
};
