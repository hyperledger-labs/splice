// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import dayjs from 'dayjs';
import { useAppForm } from '../../hooks/form';
import { CommonFormData } from './CreateProposalform';
import { dateTimeFormatISO } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useDsoInfos } from '../../contexts/SvContext';
import { ActionRequiringConfirmation } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { FormLayout } from './FormLayout';
import { Alert, Box, FormControlLabel, Radio, RadioGroup, Typography } from '@mui/material';
import { useMemo, useState } from 'react';
import {
  validateEffectiveDate,
  validateExpiration,
  validateExpiryEffectiveDate,
  validateSummary,
  validateSvSelection,
  validateUrl,
  validateWeight,
} from './formValidators';

interface ExtraFormField {
  sv: string;
  weight: string;
}

type UpdateSvRewardWeightFormData = CommonFormData & ExtraFormField;

interface UpdateSvRewardWeightFormProps {
  onSubmit: (
    data: UpdateSvRewardWeightFormData,
    action: ActionRequiringConfirmation
  ) => Promise<void>;
}

export const UpdateSvRewardWeightForm: React.FC<UpdateSvRewardWeightFormProps> = props => {
  const { onSubmit } = props;
  const [effectivityType, setEffectivityType] = useState('custom');
  const dsoInfosQuery = useDsoInfos();
  const initialExpiration = dayjs().add(
    Math.floor(
      parseInt(dsoInfosQuery.data?.dsoRules.payload.config.voteRequestTimeout.microseconds!) / 1000
    ),
    'milliseconds'
  );
  const initialEffectiveDate = dayjs(initialExpiration).add(1, 'day');

  const svs = useMemo(
    () => dsoInfosQuery.data?.dsoRules.payload.svs.entriesArray() || [],
    [dsoInfosQuery]
  );

  const svOptions: { key: string; value: string }[] = useMemo(
    () => svs.map(([partyId, svInfo]) => ({ key: svInfo.name, value: partyId })),
    [svs]
  );

  const defaultValues: UpdateSvRewardWeightFormData = {
    action: 'SRARC_UpdateSvRewardWeight',
    expiryDate: initialExpiration.format(dateTimeFormatISO),
    effectiveDate: initialEffectiveDate.format(dateTimeFormatISO),
    url: '',
    summary: '',
    sv: '',
    weight: '',
  };

  const form = useAppForm({
    defaultValues,
    onSubmit: ({ value }) => {
      const action: ActionRequiringConfirmation = {
        tag: 'ARC_DsoRules',
        value: {
          dsoAction: {
            tag: 'SRARC_UpdateSvRewardWeight',
            value: {
              svParty: value.sv,
              newRewardWeight: value.weight,
            },
          },
        },
      };
      console.log('submit sv reward weight form data: ', value, 'with action:', action);
      onSubmit(value, action);
    },

    validators: {
      onChange: ({ value }) => {
        return validateExpiryEffectiveDate({
          expiration: value.expiryDate,
          effectiveDate: value.effectiveDate,
        });
      },
    },
  });

  return (
    <FormLayout form={form} id="update-sv-reward-weight-form">
      <form.AppField name="action">
        {field => (
          <field.TextField
            title="Action"
            id="update-sv-reward-weight-action"
            muiTextFieldProps={{ disabled: true }}
          />
        )}
      </form.AppField>

      <form.AppField
        name="expiryDate"
        validators={{
          onChange: ({ value }) => validateExpiration(value),
          onBlur: ({ value }) => validateExpiration(value),
        }}
      >
        {field => (
          <field.DateField
            title="Vote Proposal Expiration"
            description="This is the last day voters can vote on this proposal"
            id="update-sv-reward-weight-expiry-date"
          />
        )}
      </form.AppField>

      <form.Field
        name="effectiveDate"
        validators={{
          onChange: ({ value }) => validateEffectiveDate(value),
          onBlur: ({ value }) => validateEffectiveDate(value),
        }}
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
                      <field.DateField
                        description="Select the date and time the proposal will take effect"
                        minDate={dayjs(form.getFieldValue('expiryDate'))}
                        id="update-sv-reward-weight-effective-date"
                      />
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
                        This will allow the vote proposal to take effect immediately when 2/3 vote
                        in favor
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

      <form.AppField
        name="summary"
        validators={{
          onBlur: ({ value }) => validateSummary(value),
          onChange: ({ value }) => validateSummary(value),
        }}
      >
        {field => <field.TextArea title="Proposal Summary" id="update-sv-reward-weight-summary" />}
      </form.AppField>

      <form.AppField
        name="url"
        validators={{
          onBlur: ({ value }) => validateUrl(value),
          onChange: ({ value }) => validateUrl(value),
        }}
      >
        {field => <field.TextField title="URL" id="update-sv-reward-weight-url" />}
      </form.AppField>

      <form.AppField
        name="sv"
        validators={{
          onBlur: ({ value }) => validateSvSelection(value),
          onChange: ({ value }) => validateSvSelection(value),
        }}
      >
        {field => (
          <field.SelectField
            title="Member"
            options={svOptions}
            id="update-sv-reward-weight-member"
          />
        )}
      </form.AppField>

      <form.AppField
        name="weight"
        validators={{
          onBlur: ({ value }) => validateWeight(value),
          onChange: ({ value }) => validateWeight(value),
        }}
      >
        {field => <field.TextField title="Weight" id="update-sv-reward-weight-weight" />}
      </form.AppField>

      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
        {form.state.errors.map((error, index) => (
          <Alert severity="error" key={index}>
            <Typography key={index} variant="h6" color="error">
              {error}
            </Typography>
          </Alert>
        ))}
      </Box>

      <form.AppForm>
        <form.FormControls />
      </form.AppForm>
    </FormLayout>
  );
};
