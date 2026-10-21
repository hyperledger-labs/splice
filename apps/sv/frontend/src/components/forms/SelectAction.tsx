// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Box, Button } from '@mui/material';
import { useForm } from '@tanstack/react-form';
import { useNavigate } from 'react-router';
import { Dropdown } from '../ui/Dropdown';
import { createProposalActions } from '../../utils/governance';

const CARD_CONTENT_WIDTH = 833;
const CARD_BG = '#1b1b1b';
const CARD_VERTICAL_PADDING = '60px';
const PLACEHOLDER_TEXT = 'Select proposal type';

const pillButtonSx = {
  height: '39px',
  px: '16px',
  py: '10px',
};

const cancelButtonSx = {
  ...pillButtonSx,
  bgcolor: 'transparent',
  '&:hover': { bgcolor: 'transparent' },
};

const nextButtonSx = () => ({
  ...pillButtonSx,
  '&:disabled': {
    bgcolor: '#696969',
    color: '#363636',
    border: 'none',
  },
});

const dropdownOptions = createProposalActions.map(action => ({
  value: action.value,
  label: action.name,
  testId: action.value,
}));

export const SelectAction: React.FC = () => {
  const navigate = useNavigate();
  const form = useForm({
    defaultValues: {
      action: '',
    },
    onSubmit: async ({ value }) => {
      navigate(`/governance/proposals/create?action=${value.action}`);
    },
  });

  const handleCancel = () => {
    form.reset();
    navigate('/governance/proposals');
  };

  return (
    <Box
      sx={{
        width: '100%',
        display: 'flex',
        justifyContent: 'center',
        py: CARD_VERTICAL_PADDING,
        px: { xs: 2, sm: 4 },
        bgcolor: CARD_BG,
        borderRadius: '4px',
      }}
    >
      <Box sx={{ width: '100%', maxWidth: CARD_CONTENT_WIDTH }}>
        <form
          onSubmit={e => {
            e.preventDefault();
            e.stopPropagation();
            form.handleSubmit();
          }}
        >
          <form.Field
            name="action"
            validators={{
              onMount: ({ value }) => {
                const res = createProposalActions.find(a => a.value === value);
                return res ? undefined : 'Invalid action';
              },
            }}
            children={field => (
              <Dropdown
                label="Select Action"
                placeholder={PLACEHOLDER_TEXT}
                options={dropdownOptions}
                value={field.state.value}
                onChange={field.handleChange}
                onBlur={field.handleBlur}
                id="select-action"
                labelId="select-action-label"
                testId="select-action"
                sx={{ mb: '32px' }}
              />
            )}
          />

          <Box
            sx={{ display: 'flex', justifyContent: 'center', alignItems: 'center', gap: '14px' }}
          >
            <form.Subscribe
              selector={state => state.canSubmit}
              children={canSubmit => (
                <>
                  <Button
                    variant="pill"
                    color="secondary"
                    size="large"
                    sx={cancelButtonSx}
                    data-testid="cancel-button"
                    onClick={handleCancel}
                    type="button"
                  >
                    Cancel
                  </Button>

                  <Button
                    variant="pill"
                    size="large"
                    sx={nextButtonSx}
                    id="next-button"
                    data-testid="next-button"
                    type="submit"
                    disabled={!canSubmit}
                  >
                    Next
                  </Button>
                </>
              )}
            />
          </Box>
        </form>
      </Box>
    </Box>
  );
};
