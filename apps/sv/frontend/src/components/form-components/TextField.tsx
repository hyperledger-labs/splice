// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Box, TextField as MuiTextField, Typography } from '@mui/material';
import { useFieldContext } from '../../hooks/formContext';

export interface TextFieldProps {
  title: string;
}

export const TextField: React.FC<TextFieldProps> = props => {
  const { title } = props;
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
        onChange={e => field.handleChange(e.target.value)}
      />
    </Box>
  );
};
