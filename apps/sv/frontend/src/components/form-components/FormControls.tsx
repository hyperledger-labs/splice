// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Box, Button } from '@mui/material';
import { useFormContext } from '../../hooks/formContext';
import { useNavigate } from 'react-router';

export interface FormControlsProps {
  showConfirmation?: boolean;
  onEdit: () => void;
}

export const FormControls: React.FC<FormControlsProps> = props => {
  const { showConfirmation, onEdit } = props;
  const form = useFormContext();
  const navigate = useNavigate();
  const submitTitle = showConfirmation ? 'Submit Proposal' : 'Review Proposal';
  const cancelTitle = showConfirmation ? 'Edit Proposal' : 'Cancel';

  const handleCancel = () => {
    if (showConfirmation) {
      onEdit();
    } else {
      navigate('/governance-beta/proposals/create');
    }
  };

  return (
    <Box
      sx={{
        display: 'flex',
        justifyContent: 'center',
        alignItems: 'center',
        spacing: 4,
      }}
      data-testid="form-controls"
    >
      <Button
        variant="outlined"
        sx={{ mr: 8 }}
        data-testid="cancel-button"
        onClick={() => handleCancel()}
      >
        {cancelTitle}
      </Button>

      <form.Subscribe
        selector={state => [state.canSubmit, state.isSubmitting]}
        children={([canSubmit, isSubmitting]) => (
          <Button
            variant="pill"
            type={'submit'}
            size="large"
            disabled={!canSubmit || isSubmitting}
            id="submit-button"
            data-testid="submit-button"
          >
            {isSubmitting ? 'Submitting' : submitTitle}
          </Button>
        )}
      />
    </Box>
  );
};
