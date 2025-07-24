// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { useSearchParams } from 'react-router-dom';
import {
  Box,
  FormControl,
  FormControlLabel,
  MenuItem,
  Paper,
  Radio,
  RadioGroup,
  Select,
  SelectChangeEvent,
  TextField,
  Typography,
} from '@mui/material';
import { useForm } from '@tanstack/react-form';
import { DesktopDateTimePicker } from '@mui/x-date-pickers';
import dayjs from 'dayjs';
import { dateTimeFormatISO } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useState } from 'react';
import { createProposalActions } from '../../utils/governance';

type CreateProposalAction = (typeof createProposalActions)[number];

interface CreateProposalFormProps {
  action: CreateProposalAction;
}

export const CreateProposalForm: React.FC<CreateProposalFormProps> = ({ action }) => {
  const [searchParams, _] = useSearchParams();
  const actionFromParams = searchParams.get('action');
  const [effectivityType, setEffectivityType] = useState('custom');

  const memberOptions: { key: string; value: string }[] = [
    { key: 'sv1', value: 'Super Validator 1' },
    { key: 'sv2', value: 'Super Validator 2' },
  ];

  const form = useForm({
    defaultValues: {
      action: action.name,
      expiryDate: dayjs(),
      effectiveDate: dayjs().add(1, 'day'),
      url: '',
      summary: '',
      member: '',
    },
  });

  if (actionFromParams !== action.value) {
    return <Typography variant="h3">Invalid action selected: {actionFromParams}</Typography>;
  }

  return (
    <>
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
          <Box sx={{ minWidth: '80%' }}>
            <form
              onSubmit={e => {
                e.preventDefault();
                e.stopPropagation();
                form.handleSubmit();
              }}
            >
              <Box sx={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                <Typography variant="h3">[Work In Progress]</Typography>
                <form.Field
                  name="action"
                  children={field => {
                    return (
                      <Box>
                        <Typography variant="h5" gutterBottom>
                          Action
                        </Typography>
                        <TextField
                          fullWidth
                          variant="outlined"
                          name={field.name}
                          value={field.state.value}
                          onBlur={field.handleBlur}
                          disabled
                        />
                      </Box>
                    );
                  }}
                />

                <form.Field
                  name="expiryDate"
                  children={field => {
                    return (
                      <Box>
                        <Typography variant="h5" gutterBottom>
                          Vote Proposal Expiration
                        </Typography>
                        <Typography variant="body2" color="text.secondary" gutterBottom>
                          This is the last day voters can vote on this proposal
                        </Typography>
                        <DesktopDateTimePicker
                          value={field.state.value}
                          format={dateTimeFormatISO}
                          onChange={newDate => field.handleChange(newDate!)}
                          slotProps={{
                            textField: {
                              fullWidth: true,
                              variant: 'outlined',
                            },
                          }}
                        />
                      </Box>
                    );
                  }}
                />

                <form.Field
                  name="effectiveDate"
                  children={field => {
                    return (
                      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                        <Typography variant="h5" gutterBottom>
                          Vote Proposal Effectivity
                        </Typography>

                        <RadioGroup
                          value={effectivityType}
                          onChange={e => setEffectivityType(e.target.value)}
                        >
                          <FormControlLabel
                            value="custom"
                            control={<Radio />}
                            label={
                              <Box>
                                <Typography>Custom</Typography>
                                <Typography variant="body2" color="text.secondary">
                                  Select the date and time the proposal will take effect
                                </Typography>
                              </Box>
                            }
                          />

                          {effectivityType === 'custom' && (
                            <DesktopDateTimePicker
                              value={field.state.value}
                              onChange={newDate => field.handleChange(newDate!)}
                              sx={{
                                width: '100%',
                                mt: 1,
                                mb: 2,
                              }}
                              slotProps={{
                                textField: {
                                  fullWidth: false,
                                  variant: 'outlined',
                                },
                              }}
                            />
                          )}

                          <FormControlLabel
                            value="threshold"
                            control={<Radio />}
                            label={
                              <Box>
                                <Typography>Make effective at threshold</Typography>
                                <Typography variant="body2" color="text.secondary">
                                  This will allow the vote proposal to take effect immediately when
                                  2/3 vote in favor
                                </Typography>
                              </Box>
                            }
                          />
                        </RadioGroup>
                      </Box>
                    );
                  }}
                />

                <form.Field
                  name="summary"
                  children={field => {
                    return (
                      <Box>
                        <Typography variant="h6" gutterBottom>
                          Proposal Summary
                          <Typography
                            component="span"
                            variant="body2"
                            color="text.secondary"
                            sx={{ ml: 1 }}
                          >
                            optional
                          </Typography>
                        </Typography>
                        <TextField
                          fullWidth
                          multiline
                          rows={5}
                          variant="outlined"
                          value={field.state.value}
                        />
                      </Box>
                    );
                  }}
                />

                <form.Field
                  name="url"
                  children={field => {
                    return (
                      <Box>
                        <Typography variant="h6" gutterBottom>
                          URL
                        </Typography>
                        <TextField
                          fullWidth
                          variant="outlined"
                          value={field.state.value}
                          onBlur={field.handleBlur}
                          onChange={e => field.handleChange(e.target.value)}
                        />
                      </Box>
                    );
                  }}
                />

                <form.Field
                  name="member"
                  children={field => {
                    return (
                      <Box>
                        <Typography variant="h6" gutterBottom>
                          Member
                        </Typography>
                        <FormControl variant="outlined" fullWidth>
                          <Select
                            value={field.state.value}
                            onChange={(e: SelectChangeEvent) =>
                              field.handleChange(e.target.value as string)
                            }
                            onBlur={field.handleBlur}
                          >
                            {memberOptions &&
                              memberOptions.map((member, index) => (
                                <MenuItem key={'member-option-' + index} value={member.key}>
                                  {member.value}
                                </MenuItem>
                              ))}
                          </Select>
                        </FormControl>
                      </Box>
                    );
                  }}
                />
              </Box>
            </form>
          </Box>
        </Paper>
      </Box>
    </>
  );
};
