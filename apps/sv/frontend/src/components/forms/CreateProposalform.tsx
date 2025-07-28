// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { useSearchParams } from 'react-router-dom';
import {
  Box,
  Button,
  FormControlLabel,
  Paper,
  Radio,
  RadioGroup,
  TextField,
  Typography,
} from '@mui/material';
import dayjs from 'dayjs';
import { dateTimeFormatISO } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useMemo, useState } from 'react';
import { createProposalActions } from '../../utils/governance';
import { useDsoInfos } from '../../contexts/SvContext';
import { buildDsoConfigChanges } from '../../utils/buildDsoConfigChanges';
import { useAppForm } from '../../hooks/form';

type ConfigFormData = Record<string, string>;

type CreateProposalAction = (typeof createProposalActions)[number];

interface CreateProposalFormProps {
  action: CreateProposalAction;
}

type FormValues = {
  action: string;
  expiryDate: string;
  effectiveDate: string;
  url: string;
  summary: string;
  member: string;
} & ConfigFormData;

const tomorrow = () => dayjs().add(1, 'day').format(dateTimeFormatISO);

export const CreateProposalForm: React.FC<CreateProposalFormProps> = ({ action }) => {
  const [searchParams, _] = useSearchParams();
  const actionFromParams = searchParams.get('action');
  const [effectivityType, setEffectivityType] = useState('custom');
  const dsoInfoQuery = useDsoInfos();

  const memberOptions: { key: string; value: string }[] = [
    { key: 'sv1', value: 'Super Validator 1' },
    { key: 'sv2', value: 'Super Validator 2' },
  ];

  const defaultValues = useMemo((): FormValues => {
    if (!dsoInfoQuery.data) {
      return {
        action: action.name,
        expiryDate: dayjs().format(dateTimeFormatISO),
        effectiveDate: tomorrow(),
        url: '',
        summary: '',
        member: '',
      };
    }

    const dsoConfig = dsoInfoQuery.data.dsoRules.payload.config;
    const dsoConfigChanges = buildDsoConfigChanges(dsoConfig, dsoConfig, true);

    return {
      action: action.name,
      expiryDate: dayjs().format(dateTimeFormatISO),
      effectiveDate: tomorrow(),
      url: '',
      summary: '',
      member: '',
      ...dsoConfigChanges.reduce((acc, field) => {
        acc[field.fieldName] = field.currentValue;
        return acc;
      }, {} as ConfigFormData),
    };
  }, [dsoInfoQuery.data, action.name]);

  const form = useAppForm({
    defaultValues,
    onSubmit: async ({ value }) => {
      const data = { ...value, effectiveDate: normalizeEffectivity(value.effectiveDate) };
      console.log('form submit', data);
    },
  });

  const normalizeEffectivity = (date: string) => {
    if (effectivityType === 'threshold') {
      return undefined;
    }
    return date;
  };

  if (actionFromParams !== action.value) {
    return <Typography variant="h3">Invalid action selected: {actionFromParams}</Typography>;
  }

  if (!dsoInfoQuery.data) {
    return <Typography variant="h3">Unable to fetch DSO info</Typography>;
  }

  const dsoConfig = dsoInfoQuery.data?.dsoRules.payload.config;
  const dsoConfigChanges = buildDsoConfigChanges(dsoConfig, dsoConfig, true);

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
                          autoComplete="off"
                          name={field.name}
                          value={field.state.value}
                          disabled
                        />
                      </Box>
                    );
                  }}
                />

                <form.AppField name="expiryDate">
                  {field => (
                    <field.DateField
                      title="Vote Proposal Expiration"
                      description="This is the last day voters can vote on this proposal"
                    />
                  )}
                </form.AppField>

                <form.Field
                  name="effectiveDate"
                  children={_ => {
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
                            label={<Typography>Custom</Typography>}
                          />

                          {effectivityType === 'custom' && (
                            <form.AppField name="effectiveDate">
                              {field => (
                                <field.DateField description="Select the date and time the proposal will take effect" />
                              )}
                            </form.AppField>
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
                            sx={{ mt: 2 }}
                          />
                        </RadioGroup>
                      </Box>
                    );
                  }}
                />

                <form.AppField name="summary">
                  {field => <field.TextArea title="Proposal Summary" optional />}
                </form.AppField>

                <form.AppField name="url">{field => <field.TextField title="URL" />}</form.AppField>

                <form.AppField name="member">
                  {field => <field.SelectField title="Member" options={memberOptions} />}
                </form.AppField>

                <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                  <Typography variant="h6" gutterBottom>
                    Configuration
                  </Typography>

                  {dsoConfigChanges.map((change, index) => (
                    <form.AppField name={change.fieldName} key={index}>
                      {field => <field.ConfigField configChange={change} key={index} />}
                    </form.AppField>
                  ))}
                </Box>

                <Box
                  sx={{
                    display: 'flex',
                    justifyContent: 'center',
                    alignItems: 'center',
                    mt: 4,
                    spacing: 4,
                  }}
                >
                  <Button variant="outlined" sx={{ mr: 8 }}>
                    Cancel
                  </Button>
                  <form.Subscribe
                    selector={state => [state.canSubmit, state.isSubmitting]}
                    children={([canSubmit, isSubmitting]) => (
                      <Button
                        variant="pill"
                        type={'submit'}
                        size="large"
                        disabled={!canSubmit || isSubmitting}
                      >
                        {isSubmitting ? 'Submitting' : 'Submit Proposal'}
                      </Button>
                    )}
                  />
                </Box>
              </Box>
            </form>
          </Box>
        </Paper>
      </Box>
    </>
  );
};
