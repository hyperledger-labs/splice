// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import dayjs from 'dayjs';
import { ConfigFieldState } from '../form-components/ConfigField';
import { dateTimeFormatISO } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { ConfigChange } from '../../utils/types';
import { createProposalActions, getInitialExpiration } from '../../utils/governance';
import { useDsoInfos } from '../../contexts/SvContext';
import { useMemo, useState } from 'react';
import { buildDsoConfigChanges } from '../../utils/buildDsoConfigChanges';
import { useAppForm } from '../../hooks/form';
import {
  validateEffectiveDate,
  validateExpiryEffectiveDate,
  validateSummary,
  validateUrl,
} from './formValidators';
import { FormLayout } from './FormLayout';
import { Alert, Box, FormControlLabel, Radio, RadioGroup, Typography } from '@mui/material';
import { ActionRequiringConfirmation } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';

type ConfigFormData = Record<string, ConfigFieldState>;

export interface CommonFormData {
  action: string;
  expiryDate: string;
  effectiveDate: string;
  url: string;
  summary: string;
}

export type CompleteFormData = {
  common: CommonFormData;
  config: ConfigFormData;
};

export function configFormDataToConfigChanges(
  formData: ConfigFormData,
  dsoConfigChanges: ConfigChange[]
): ConfigChange[] {
  const configChanges: ConfigChange[] = dsoConfigChanges.map(change => {
    const fieldState = formData[change.fieldName];
    return {
      fieldName: change.fieldName,
      label: change.label,
      currentValue: change.currentValue,
      newValue: fieldState?.value || '',
    };
  });
  return configChanges;
}
const createProposalAction = createProposalActions.find(a => a.value === 'SRARC_SetConfig');

export interface SetDsoConfigRulesFormProps {
  onSubmit: (data: CompleteFormData, action: ActionRequiringConfirmation) => Promise<void>;
}

export const SetDsoConfigRulesForm: React.FC<SetDsoConfigRulesFormProps> = _ => {
  const [effectivityType, setEffectivityType] = useState('custom');
  const dsoInfoQuery = useDsoInfos();
  const initialExpiration = getInitialExpiration(dsoInfoQuery.data);
  const initialEffectiveDate = dayjs(initialExpiration).add(1, 'day');

  const defaultValues = useMemo((): CompleteFormData => {
    if (!dsoInfoQuery.data) {
      return {
        common: {
          action: createProposalAction?.name || '',
          expiryDate: initialExpiration.format(dateTimeFormatISO),
          effectiveDate: initialEffectiveDate.format(dateTimeFormatISO),
          url: '',
          summary: '',
        },
        config: {},
      };
    }

    const dsoConfig = dsoInfoQuery.data.dsoRules.payload.config;
    const dsoConfigChanges = buildDsoConfigChanges(dsoConfig, dsoConfig, true);

    return {
      common: {
        action: createProposalAction?.name || '',
        expiryDate: initialExpiration.format(dateTimeFormatISO),
        effectiveDate: initialEffectiveDate.format(dateTimeFormatISO),
        url: '',
        summary: '',
      },
      config: dsoConfigChanges.reduce((acc, field) => {
        acc[field.fieldName] = { fieldName: field.fieldName, value: field.currentValue };
        return acc;
      }, {} as ConfigFormData),
    };
  }, [dsoInfoQuery.data, initialExpiration, initialEffectiveDate]);

  const form = useAppForm({
    defaultValues,
    onSubmit: async ({ value }) => {
      console.log('form submit', value);
    },
    validators: {
      onChange: ({ value }) => {
        return validateExpiryEffectiveDate({
          expiration: value.common.expiryDate,
          effectiveDate: value.common.effectiveDate,
        });
      },
    },
  });

  const maybeConfig = dsoInfoQuery.data?.dsoRules.payload.config;
  const dsoConfig = maybeConfig ? maybeConfig : null;
  // passing the config twice here because we initially have no changes
  const dsoConfigChanges = buildDsoConfigChanges(dsoConfig, dsoConfig, true);

  return (
    <FormLayout form={form} id="set-dso-config-rules-form">
      <form.AppField name="common.action">
        {field => (
          <field.TextField
            title="Action"
            id="set-dso-config-rules-action"
            muiTextFieldProps={{ disabled: true }}
          />
        )}
      </form.AppField>

      <form.AppField name="common.expiryDate">
        {field => (
          <field.DateField
            title="Vote Proposal Expiration"
            description="This is the last day voters can vote on this proposal"
            id="set-dso-config-rules-expiry-date"
          />
        )}
      </form.AppField>

      <form.Field
        name="common.effectiveDate"
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
                  <form.AppField name="common.effectiveDate">
                    {field => (
                      <field.DateField
                        description="Select the date and time the proposal will take effect"
                        minDate={dayjs(form.getFieldValue('common.expiryDate'))}
                        id="set-dso-config-rules-effective-date"
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
        name="common.summary"
        validators={{
          onBlur: ({ value }) => validateSummary(value),
          onChange: ({ value }) => validateSummary(value),
        }}
      >
        {field => <field.TextArea title="Proposal Summary" id="set-dso-config-rules-summary" />}
      </form.AppField>

      <form.AppField
        name="common.url"
        validators={{
          onBlur: ({ value }) => validateUrl(value),
          onChange: ({ value }) => validateUrl(value),
        }}
      >
        {field => <field.TextField title="URL" id="set-dso-config-rules-url" />}
      </form.AppField>

      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
        <Typography variant="h6" gutterBottom>
          Configuration
        </Typography>

        {dsoConfigChanges.map((change, index) => (
          <form.AppField name={`config.${change.fieldName}`} key={index}>
            {field => <field.ConfigField configChange={change} key={index} />}
          </form.AppField>
        ))}
      </Box>

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
