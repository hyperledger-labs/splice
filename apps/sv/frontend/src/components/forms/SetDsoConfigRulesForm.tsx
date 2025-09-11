// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import dayjs from 'dayjs';
import { dateTimeFormatISO } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { CommonProposalFormData, ConfigFormData } from '../../utils/types';
import {
  configFormDataToConfigChanges,
  createProposalActions,
  getInitialExpiration,
} from '../../utils/governance';
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
import { Box, Typography } from '@mui/material';
import { ActionRequiringConfirmation } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { EffectiveDateField } from '../form-components/EffectiveDateField';
import { useMemo, useState } from 'react';
import { ProposalSummary } from '../governance/ProposalSummary';
import { useProposalMutation } from '../../hooks/useProposalMutation';
import { ProposalSubmissionError } from '../form-components/ProposalSubmissionError';
import { buildDsoRulesConfigFromChanges } from '../../utils/buildDsoRulesConfigFromChanges';

export type SetDsoConfigCompleteFormData = {
  common: CommonProposalFormData;
  config: ConfigFormData;
};

const createProposalAction = createProposalActions.find(a => a.value === 'SRARC_SetConfig');

export const SetDsoConfigRulesForm: () => JSX.Element = () => {
  const dsoInfoQuery = useDsoInfos();
  const initialExpiration = getInitialExpiration(dsoInfoQuery.data);
  const initialEffectiveDate = dayjs(initialExpiration).add(1, 'day');
  const mutation = useProposalMutation();
  const [showConfirmation, setShowConfirmation] = useState(false);

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
    onSubmit: async ({ value: formData }) => {
      if (!showConfirmation) {
        setShowConfirmation(true);
      } else {
        const changes = configFormDataToConfigChanges(formData.config, dsoConfigChanges, false);
        const baseConfig = dsoConfig;
        const newConfig = buildDsoRulesConfigFromChanges(changes);
        const action: ActionRequiringConfirmation = {
          tag: 'ARC_DsoRules',
          value: {
            dsoAction: {
              tag: 'SRARC_SetConfig',
              value: {
                baseConfig: baseConfig,
                newConfig: newConfig,
              },
            },
          },
        };

        await mutation.mutateAsync({ formData, action }).catch(e => {
          console.error(`Failed to submit proposal`, e);
        });
      }
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
      {showConfirmation ? (
        <ProposalSummary
          actionName={form.state.values.common.action}
          url={form.state.values.common.url}
          summary={form.state.values.common.summary}
          expiryDate={form.state.values.common.expiryDate}
          effectiveDate={form.state.values.common.effectiveDate.effectiveDate}
          formType="config-change"
          configFormData={configFormDataToConfigChanges(form.state.values.config, dsoConfigChanges)}
          onEdit={() => setShowConfirmation(false)}
          onSubmit={() => {}}
        />
      ) : (
        <>
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
        </>
      )}

      <form.AppForm>
        <ProposalSubmissionError error={mutation.error} />
        <form.FormErrors />
        <form.FormControls
          showConfirmation={showConfirmation}
          onEdit={() => setShowConfirmation(false)}
        />
      </form.AppForm>
    </FormLayout>
  );
};
