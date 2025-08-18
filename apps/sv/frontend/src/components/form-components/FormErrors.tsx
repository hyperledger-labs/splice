// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Alert, Box } from '@mui/material';
import { useFormContext } from '../../hooks/formContext';

export const FormErrors: React.FC = _ => {
  const form = useFormContext();

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
      <form.Subscribe
        selector={state => state.errors}
        children={errors =>
          errors.map((error, index) => (
            <Alert severity="error" key={index}>
              {error}
            </Alert>
          ))
        }
      />
    </Box>
  );
};
