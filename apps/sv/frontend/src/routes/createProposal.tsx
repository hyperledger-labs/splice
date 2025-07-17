// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import {
  Box,
  Button,
  FormControl,
  MenuItem,
  Paper,
  Select,
  SelectChangeEvent,
  Typography,
} from '@mui/material';
import { useForm } from '@tanstack/react-form';
import { useNavigate, useSearchParams } from 'react-router-dom';

const actionNameTable = [
  { name: 'Offboard Member', value: 'SRARC_OffboardSv' },
  { name: 'Feature Application', value: 'SRARC_GrantFeaturedAppRight' },
  { name: 'Unfeature Application', value: 'SRARC_RevokeFeaturedAppRight' },
  { name: 'Set Dso Rules Configuration', value: 'SRARC_SetConfig' },
  { name: 'Set Amulet Rules Configuration', value: 'CRARC_SetConfig' },
  { name: 'Update SV Reward Weight', value: 'SRARC_UpdateSvRewardWeight' },
];

export const CreateProposal: React.FC = () => {
  const [searchParams, _] = useSearchParams();
  const action = searchParams.get('action');
  const selectedAction = actionNameTable.find(a => a.value === action);
  if (selectedAction) {
    return <CreateProposalForm action={selectedAction.value} />;
  } else {
    return <SelectAction />;
  }
};

interface CreateProposalFormProps {
  action: string;
}

const CreateProposalForm: React.FC<CreateProposalFormProps> = () => {
  const [searchParams, _] = useSearchParams();
  return (
    <>
      <h1>Create Proposal Form</h1>
      <p>Action: {searchParams.get('action')}</p>
    </>
  );
};

const SelectAction: React.FC = () => {
  const navigate = useNavigate();

  const form = useForm({
    defaultValues: {
      action: '',
    },
    onSubmit: async ({ value }) => {
      navigate(`/governance-beta/proposals/create?action=${value.action}`);
    },
  });

  const handleCancel = () => {
    form.reset();
    navigate('/governance-beta/proposals');
  };

  return (
    <Box sx={{ mt: 10 }}>
      <Paper
        sx={{
          bgcolor: 'background.paper',
          p: 4,
          display: 'flex',
          justifyContent: 'center',
          alignItems: 'center',
        }}
      >
        <Box sx={{ minWidth: '60%' }}>
          <Typography sx={{ mb: 2 }} variant="h3">
            Select an Action
          </Typography>

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
                  const res = actionNameTable.find(a => a.value === value);
                  return res ? undefined : 'Invalid action';
                },
              }}
              children={field => (
                <FormControl fullWidth>
                  <Select
                    labelId="select-action-label"
                    id="select-action"
                    data-testid="select-action"
                    value={field.state.value}
                    onChange={(e: SelectChangeEvent) =>
                      field.handleChange(e.target.value as string)
                    }
                    onBlur={field.handleBlur}
                    placeholder="Select an action"
                  >
                    {actionNameTable.map(actionName => (
                      <MenuItem
                        key={actionName.value}
                        value={actionName.value}
                        data-testid={actionName.value}
                      >
                        {actionName.name}
                      </MenuItem>
                    ))}
                  </Select>
                </FormControl>
              )}
            />

            <Box sx={{ display: 'flex', justifyContent: 'space-between', mt: 4 }}>
              <form.Subscribe
                selector={state => state.canSubmit}
                children={canSubmit => (
                  <>
                    <Button
                      variant="outlined"
                      data-testid="cancel-button"
                      onClick={handleCancel}
                      type="button"
                    >
                      Cancel
                    </Button>

                    <Button
                      variant="contained"
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
      </Paper>
    </Box>
  );
};
