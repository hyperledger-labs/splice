// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import dayjs from 'dayjs';
import { ConfigFieldState } from '../form-components/ConfigField';
import { dateTimeFormatISO } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { CommonProposalFormData, ConfigChange } from '../../utils/types';
import { createProposalActions, getInitialExpiration } from '../../utils/governance';
import { useDsoInfos } from '../../contexts/SvContext';
import { buildDsoConfigChanges } from '../../utils/buildDsoConfigChanges';
import { useAppForm } from '../../hooks/form';
import {
  validateEffectiveDate,
  validateExpiryEffectiveDate,
  validateSummary,
  validateUrl,
} from './formValidators';
import { FormLayout } from './FormLayout';
import { Alert, Box, Typography } from '@mui/material';
import { ActionRequiringConfirmation } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { EffectiveDateField } from '../form-components/EffectiveDateField';
import { useMemo } from 'react';

type ConfigFormData = Record<string, ConfigFieldState>;

type SetDsoConfigCompleteFormData = {
  common: CommonProposalFormData;
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
  onSubmit: (
    data: SetDsoConfigCompleteFormData,
    action: ActionRequiringConfirmation
  ) => Promise<void>;
}

export const SetDsoConfigRulesForm: React.FC<SetDsoConfigRulesFormProps> = _ => {
  const dsoInfoQuery = useDsoInfos();
  const initialExpiration = getInitialExpiration(dsoInfoQuery.data);
  const initialEffectiveDate = dayjs(initialExpiration).add(1, 'day');

  const defaultValues = useMemo((): SetDsoConfigCompleteFormData => {
    if (!dsoInfoQuery.data) {
      return {
        common: {
          action: createProposalAction?.name || '',
          expiryDate: initialExpiration.format(dateTimeFormatISO),
          effectiveDate: {
            type: 'custom',
            effectiveDate: initialEffectiveDate.format(dateTimeFormatISO),
          },
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
        effectiveDate: {
          type: 'custom',
          effectiveDate: initialEffectiveDate.format(dateTimeFormatISO),
        },
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
          effectiveDate: value.common.effectiveDate.effectiveDate,
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

      <form.AppField
        name="common.effectiveDate"
        validators={{
          onChange: ({ value }) => validateEffectiveDate(value),
          onBlur: ({ value }) => validateEffectiveDate(value),
        }}
        children={_ => (
          <EffectiveDateField
            initialEffectiveDate={initialEffectiveDate.format(dateTimeFormatISO)}
            id="set-dso-config-rules-effective-date"
          />
        )}
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
