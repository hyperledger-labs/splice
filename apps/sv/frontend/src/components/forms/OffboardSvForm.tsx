// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { ActionRequiringConfirmation } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { useAppForm } from '../../hooks/form';
import { useDsoInfos } from '../../contexts/SvContext';
import dayjs from 'dayjs';
import { createProposalActions, getInitialExpiration } from '../../utils/governance';
import { dateTimeFormatISO } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import {
  validateEffectiveDate,
  validateExpiration,
  validateExpiryEffectiveDate,
  validateSummary,
  validateSvSelection,
  validateUrl,
} from './formValidators';
import { FormLayout } from './FormLayout';
import { Alert, Box, Typography } from '@mui/material';
import { useMemo } from 'react';
import { CommonProposalFormData } from '../../utils/types';
import { EffectiveDateField } from '../form-components/EffectiveDateField';

interface ExtraFormFields {
  sv: string;
}

type OffboardSvFormData = CommonProposalFormData & ExtraFormFields;

export interface OffboardSvFormProps {
  onSubmit: (data: OffboardSvFormData, action: ActionRequiringConfirmation) => Promise<void>;
}

export const OffboardSvForm: React.FC<OffboardSvFormProps> = props => {
  const { onSubmit } = props;

  const dsoInfosQuery = useDsoInfos();
  const initialExpiration = getInitialExpiration(dsoInfosQuery.data);
  const initialEffectiveDate = dayjs(initialExpiration).add(1, 'day');

  const svs = useMemo(
    () => dsoInfosQuery.data?.dsoRules.payload.svs.entriesArray() || [],
    [dsoInfosQuery]
  );

  const svOptions: { key: string; value: string }[] = useMemo(
    () => svs.map(([partyId, svInfo]) => ({ key: svInfo.name, value: partyId })),
    [svs]
  );

  const createProposalAction = createProposalActions.find(a => a.value === 'SRARC_OffboardSv');

  const defaultValues: OffboardSvFormData = {
    action: createProposalAction?.name || '',
    expiryDate: initialExpiration.format(dateTimeFormatISO),
    effectiveDate: {
      type: 'custom',
      effectiveDate: initialEffectiveDate.format(dateTimeFormatISO),
    },
    url: '',
    summary: '',
    sv: '',
  };

  const form = useAppForm({
    defaultValues,
    onSubmit: ({ value }) => {
      const action: ActionRequiringConfirmation = {
        tag: 'ARC_DsoRules',
        value: {
          dsoAction: {
            tag: 'SRARC_OffboardSv',
            value: {
              sv: value.sv,
            },
          },
        },
      };
      console.log('submit offboard sv form data: ', value, 'with action:', action);
      onSubmit(value, action);
    },
    validators: {
      onChange: ({ value }) => {
        return validateExpiryEffectiveDate({
          expiration: value.expiryDate,
          effectiveDate: value.effectiveDate.effectiveDate,
        });
      },
    },
  });

  return (
    <FormLayout form={form} id="offboard-sv-form">
      <form.AppField name="action">
        {field => (
          <field.TextField
            title="Action"
            id="offboard-sv-action"
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
            id="offboard-sv-expiry-date"
          />
        )}
      </form.AppField>

      <form.AppField
        name="effectiveDate"
        validators={{
          onChange: ({ value }) => validateEffectiveDate(value),
          onBlur: ({ value }) => validateEffectiveDate(value),
        }}
        children={_ => (
          <EffectiveDateField
            title="Vote Proposal Effectivity"
            description="Select the date and time the proposal will take effect"
            initialEffectiveDate={initialEffectiveDate.format(dateTimeFormatISO)}
            id="offboard-sv-effective-date"
          />
        )}
      />

      <form.AppField
        name="summary"
        validators={{
          onBlur: ({ value }) => validateSummary(value),
          onChange: ({ value }) => validateSummary(value),
        }}
      >
        {field => <field.TextArea title="Proposal Summary" id="offboard-sv-summary" />}
      </form.AppField>

      <form.AppField
        name="url"
        validators={{
          onBlur: ({ value }) => validateUrl(value),
          onChange: ({ value }) => validateUrl(value),
        }}
      >
        {field => <field.TextField title="URL" id="offboard-sv-url" />}
      </form.AppField>

      <form.AppField
        name="sv"
        validators={{
          onBlur: ({ value }) => validateSvSelection(value),
          onChange: ({ value }) => validateSvSelection(value),
        }}
      >
        {field => <field.SelectField title="Member" options={svOptions} id="offboard-sv-member" />}
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
        <form.FormErrors />
        <form.FormControls />
      </form.AppForm>
    </FormLayout>
  );
};
