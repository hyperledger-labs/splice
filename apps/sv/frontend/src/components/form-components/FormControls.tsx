// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Box, Button } from '@mui/material';
import { useFormContext } from '../../hooks/formContext';

export interface FormControlsProps {
  cancelTitle?: string;
  submitTitle?: string;
}

export const FormControls: React.FC<FormControlsProps> = props => {
  const { cancelTitle, submitTitle } = props;
  const form = useFormContext();

  return (
    <Box
      sx={{
        display: 'flex',
        justifyContent: 'center',
        alignItems: 'center',
        mt: 4,
        spacing: 4,
      }}
      data-testid="form-controls"
    >
      <Button variant="outlined" sx={{ mr: 8 }} data-testid="cancel-button">
        {cancelTitle || 'Cancel'}
      </Button>

      <form.Subscribe
        selector={state => [state.canSubmit, state.isSubmitting]}
        children={([canSubmit, isSubmitting]) => (
          <Button
            variant="pill"
            type={'submit'}
            size="large"
            disabled={!canSubmit || isSubmitting}
            data-testid="submit-button"
          >
            {isSubmitting ? 'Submitting' : submitTitle || 'Submit Proposal'}
          </Button>
        )}
      />
    </Box>
  );
};
