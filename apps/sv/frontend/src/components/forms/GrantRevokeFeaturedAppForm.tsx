// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { ActionRequiringConfirmation } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { CommonFormData } from './CreateProposalform';
import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useDsoInfos } from '../../contexts/SvContext';
import dayjs from 'dayjs';
import { createProposalActions, getInitialExpiration } from '../../utils/governance';
import { dateTimeFormatISO } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useAppForm } from '../../hooks/form';
import { SupportedActionTag } from '../../utils/types';
import { ContractId } from '@daml/types';
import { FeaturedAppRight } from '@daml.js/splice-amulet/lib/Splice/Amulet';
import {
  validateEffectiveDate,
  validateExpiration,
  validateExpiryEffectiveDate,
  validateGrantRevokeFeaturedAppRight,
  validateSummary,
  validateUrl,
} from './formValidators';
import { FormLayout } from './FormLayout';
import { Alert, Box, FormControlLabel, Radio, RadioGroup, Typography } from '@mui/material';

interface ExtraFormField {
  // represents appId for granting and contractId for revoking
  idValue: string;
}

type GrantRevokeFeaturedAppFormData = CommonFormData & ExtraFormField;

export interface GrantRevokeFeaturedAppFormProps {
  selectedAction: GrantRevokeFeaturedAppActions;
  onSubmit: (
    data: GrantRevokeFeaturedAppFormData,
    action: ActionRequiringConfirmation
  ) => Promise<void>;
}

export type GrantRevokeFeaturedAppActions = Extract<
  SupportedActionTag,
  'SRARC_GrantFeaturedAppRight' | 'SRARC_RevokeFeaturedAppRight'
>;

export const GrantRevokeFeaturedAppForm: React.FC<GrantRevokeFeaturedAppFormProps> = props => {
  const { onSubmit, selectedAction } = props;
  const [effectivityType, setEffectivityType] = useState('custom');
  const dsoInfosQuery = useDsoInfos();
  const initialExpiration = getInitialExpiration(dsoInfosQuery.data);
  const initialEffectiveDate = dayjs(initialExpiration).add(1, 'day');

  const formAction: GrantRevokeFeaturedAppActions =
    (useSearchParams()[0]?.get('action') as GrantRevokeFeaturedAppActions) || selectedAction;
  const idValueFieldTitle =
    formAction === 'SRARC_GrantFeaturedAppRight'
      ? 'Provider'
      : 'Featured Application Right Contract Id';
  const testIdPrefix =
    formAction === 'SRARC_GrantFeaturedAppRight' ? 'grant-featured-app' : 'revoke-featured-app';

  const createProposalAction = createProposalActions.find(a => a.value === formAction);

  const defaultValues: GrantRevokeFeaturedAppFormData = {
    action: createProposalAction?.name || '',
    expiryDate: initialExpiration.format(dateTimeFormatISO),
    effectiveDate: initialEffectiveDate.format(dateTimeFormatISO),
    url: '',
    summary: '',
    idValue: '',
  };

  const form = useAppForm({
    defaultValues,
    onSubmit: ({ value }) => {
      const actionMap: Record<
        'SRARC_GrantFeaturedAppRight' | 'SRARC_RevokeFeaturedAppRight',
        (idValue: string) => ActionRequiringConfirmation
      > = {
        SRARC_GrantFeaturedAppRight: (idValue: string) => ({
          tag: 'ARC_DsoRules',
          value: {
            dsoAction: {
              tag: 'SRARC_GrantFeaturedAppRight',
              value: { provider: idValue },
            },
          },
        }),
        SRARC_RevokeFeaturedAppRight: (idValue: string) => ({
          tag: 'ARC_DsoRules',
          value: {
            dsoAction: {
              tag: 'SRARC_RevokeFeaturedAppRight',
              value: { rightCid: idValue as ContractId<FeaturedAppRight> },
            },
          },
        }),
      };

      const action = actionMap[formAction](value.idValue);

      console.log(`submit ${formAction} sv form data: `, value, 'with action:', action);
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
    <FormLayout form={form} id={`${testIdPrefix}-form`}>
      <form.AppField name="action">
        {field => (
          <field.TextField
            title="Action"
            id={`${testIdPrefix}-action`}
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
            id={`${testIdPrefix}-expiry-date`}
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
                        id={`${testIdPrefix}-effective-date`}
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
        {field => <field.TextArea title="Proposal Summary" id={`${testIdPrefix}-summary`} />}
      </form.AppField>

      <form.AppField
        name="url"
        validators={{
          onBlur: ({ value }) => validateUrl(value),
          onChange: ({ value }) => validateUrl(value),
        }}
      >
        {field => <field.TextField title="URL" id={`${testIdPrefix}-url`} />}
      </form.AppField>

      <form.AppField
        name="idValue"
        validators={{
          onBlur: ({ value }) => validateGrantRevokeFeaturedAppRight(value),
          onChange: ({ value }) => validateGrantRevokeFeaturedAppRight(value),
        }}
      >
        {field => <field.TextField title={idValueFieldTitle} id={`${testIdPrefix}-idValue`} />}
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
