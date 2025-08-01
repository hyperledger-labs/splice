// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Box, Paper } from '@mui/material';

export interface FormLayoutProps {
  children: React.ReactNode;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  form: any;
  id: string;
}

export const FormLayout: React.FC<FormLayoutProps> = props => {
  const { children, form, id } = props;

  return (
    <Box sx={{ mt: 10 }} data-testid={id} id={id}>
      <Paper
        sx={{
          bgcolor: 'background.paper',
          p: 4,
          display: 'flex',
          justifyContent: 'center',
          alignItems: 'center',
        }}
      >
        <Box sx={{ minWidth: '80%' }}>
          <form
            onSubmit={e => {
              e.preventDefault();
              e.stopPropagation();
              form.handleSubmit();
            }}
          >
            <Box sx={{ display: 'flex', flexDirection: 'column', gap: 8 }}>{children}</Box>
          </form>
        </Box>
      </Paper>
    </Box>
  );
};
