// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Box, TextField as MuiTextField, Typography } from '@mui/material';
import { useFieldContext } from '../../hooks/formContext';

export interface TextAreaProps {
  title: string;
  optional?: boolean;
  id: string;
}

export const TextArea: React.FC<TextAreaProps> = props => {
  const { title, optional, id } = props;
  const field = useFieldContext<string>();
  return (
    <Box>
      <Typography variant="h6" gutterBottom>
        {title}
        {optional && (
          <Typography component="span" variant="body2" color="text.secondary" sx={{ ml: 1 }}>
            optional
          </Typography>
        )}
      </Typography>
      <MuiTextField
        fullWidth
        multiline
        rows={5}
        variant="outlined"
        autoComplete="off"
        value={field.state.value}
        onBlur={field.handleBlur}
        onChange={e => field.handleChange(e.target.value)}
        error={!field.state.meta.isValid}
        helperText={field.state.meta.errors?.[0]}
        inputProps={{ 'data-testid': id }}
      />
    </Box>
  );
};
