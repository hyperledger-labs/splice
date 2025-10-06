// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { ActionRequiringConfirmation } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import {
  buildAmuletRulesPendingConfigFields,
  configFormDataToConfigChanges,
  createProposalActions,
  getInitialExpiration,
} from '../../utils/governance';
import { CommonProposalFormData, ConfigFormData } from '../../utils/types';
import dayjs from 'dayjs';
import { useDsoInfos } from '../../contexts/SvContext';
import { useMemo, useState } from 'react';
import { dateTimeFormatISO } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { buildAmuletConfigChanges } from '../../utils/buildAmuletConfigChanges';
import { useAppForm } from '../../hooks/form';
import {
  validateEffectiveDate,
  validateExpiryEffectiveDate,
  validateSummary,
  validateUrl,
} from './formValidators';
import { FormLayout } from './FormLayout';
import { EffectiveDateField } from '../form-components/EffectiveDateField';
import { Alert, Box, Typography } from '@mui/material';
import { ProposalSummary } from '../governance/ProposalSummary';
import { buildAmuletRulesConfigFromChanges } from '../../utils/buildAmuletRulesConfigFromChanges';
import { useProposalMutation } from '../../hooks/useProposalMutation';
import { ProposalSubmissionError } from '../form-components/ProposalSubmissionError';
import { useListDsoRulesVoteRequests } from '../../hooks';

export type SetAmuletConfigCompleteFormData = {
  common: CommonProposalFormData;
  config: ConfigFormData;
};

const createProposalAction = createProposalActions.find(a => a.value === 'CRARC_SetConfig');

export const SetAmuletConfigRulesForm: () => JSX.Element = () => {
  const dsoInfoQuery = useDsoInfos();
  const mutation = useProposalMutation();
  const dsoProposalsQuery = useListDsoRulesVoteRequests();
  const initialExpiration = getInitialExpiration(dsoInfoQuery.data);
  const initialEffectiveDate = dayjs(initialExpiration).add(1, 'day');
  const [showConfirmation, setShowConfirmation] = useState(false);
  const pendingConfigFields = useMemo(
    () => buildAmuletRulesPendingConfigFields(dsoProposalsQuery.data),
    [dsoProposalsQuery.data]
  );

  const defaultValues = useMemo((): SetAmuletConfigCompleteFormData => {
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

    const amuletConfig = dsoInfoQuery.data?.amuletRules.payload.configSchedule.initialValue;
    const amuletConfigChanges = buildAmuletConfigChanges(amuletConfig, amuletConfig, true);

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
      config: amuletConfigChanges.reduce((acc, field) => {
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
        if (!amuletConfig) {
          throw new Error('Amulet Config is not defined');
        }

        const changes = configFormDataToConfigChanges(
          formData.config,
          allAmuletConfigChanges,
          false
        );
        const baseConfig = amuletConfig;
        const newConfig = buildAmuletRulesConfigFromChanges(changes);
        const action: ActionRequiringConfirmation = {
          tag: 'ARC_AmuletRules',
          value: {
            amuletRulesAction: {
              tag: 'CRARC_SetConfig',
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
      onSubmit: ({ value: formData }) => {
        const changes = configFormDataToConfigChanges(formData.config, allAmuletConfigChanges);

        const conflictingChanges = changes.filter(c =>
          pendingConfigFields.some(p => p.fieldName === c.fieldName)
        );
        const names = conflictingChanges.map(c => c.label).join(', ');

        if (conflictingChanges.length > 0) {
          return `Cannot modify fields that have pending changes (${names})`;
        }
      },
    },
  });

  const maybeConfig = dsoInfoQuery.data?.amuletRules.payload.configSchedule.initialValue;
  const amuletConfig = maybeConfig ? maybeConfig : null;
  // passing the config twice here because we initially have no changes
  const allAmuletConfigChanges = buildAmuletConfigChanges(amuletConfig, amuletConfig, true);

  return (
    <FormLayout form={form} id="set-amulet-config-rules-form">
      {showConfirmation ? (
        <ProposalSummary
          actionName={form.state.values.common.action}
          url={form.state.values.common.url}
          summary={form.state.values.common.summary}
          expiryDate={form.state.values.common.expiryDate}
          effectiveDate={form.state.values.common.effectiveDate.effectiveDate}
          formType="config-change"
          configFormData={configFormDataToConfigChanges(
            form.state.values.config,
            allAmuletConfigChanges
          )}
          onEdit={() => setShowConfirmation(false)}
          onSubmit={() => {}}
        />
      ) : (
        <>
          {pendingConfigFields.length > 0 && (
            <Alert severity="info" color="warning" variant="outlined">
              Some fields are disabled for editing due to pending votes.
            </Alert>
          )}

          <form.AppField name="common.action">
            {field => (
              <field.TextField
                title="Action"
                id="set-amulet-config-rules-action"
                muiTextFieldProps={{ disabled: true }}
              />
            )}
          </form.AppField>

          <form.AppField name="common.expiryDate">
            {field => (
              <field.DateField
                title="Threshold Deadline"
                description="This is the last day voters can vote on this proposal"
                id="set-amulet-config-rules-expiry-date"
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
                id="set-amulet-config-rules-effective-date"
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
            {field => (
              <field.TextArea title="Proposal Summary" id="set-amulet-config-rules-summary" />
            )}
          </form.AppField>

          <form.AppField
            name="common.url"
            validators={{
              onBlur: ({ value }) => validateUrl(value),
              onChange: ({ value }) => validateUrl(value),
            }}
          >
            {field => <field.TextField title="URL" id="set-amulet-config-rules-url" />}
          </form.AppField>

          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            <Typography variant="h6" gutterBottom>
              Configuration
            </Typography>

            {allAmuletConfigChanges.map((change, index) => (
              <form.AppField name={`config.${change.fieldName}`} key={index}>
                {field => (
                  <field.ConfigField
                    configChange={change}
                    key={index}
                    pendingFieldInfo={pendingConfigFields.find(
                      f => f.fieldName === change.fieldName
                    )}
                  />
                )}
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
