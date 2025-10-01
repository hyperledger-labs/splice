// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import type {
  ActionRequiringConfirmation,
  DsoRules_ActionRequiringConfirmation,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import {
  getDsoConfigToCompareWith,
  useVotesHooks,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { dateTimeFormatISO } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { Alert, Box, Typography } from '@mui/material';
import dayjs from 'dayjs';
import { useMemo, useState } from 'react';
import { PrettyJsonDiff } from '../../../../../common/frontend/lib/components/PrettyJsonDiff';
import { useDsoInfos } from '../../contexts/SvContext';
import { useListDsoRulesVoteRequests } from '../../hooks';
import { useAppForm } from '../../hooks/form';
import { useProposalMutation } from '../../hooks/useProposalMutation';
import { buildDsoConfigChanges } from '../../utils/buildDsoConfigChanges';
import { buildDsoRulesConfigFromChanges } from '../../utils/buildDsoRulesConfigFromChanges';
import {
  buildPendingConfigFields,
  configFormDataToConfigChanges,
  createProposalActions,
  getInitialExpiration,
} from '../../utils/governance';
import type { CommonProposalFormData, ConfigFormData } from '../../utils/types';
import { EffectiveDateField } from '../form-components/EffectiveDateField';
import { ProposalSubmissionError } from '../form-components/ProposalSubmissionError';
import { JsonDiffAccordion } from '../governance/JsonDiffAccordion';
import { ProposalSummary } from '../governance/ProposalSummary';
import { FormLayout } from './FormLayout';
import {
  validateEffectiveDate,
  validateExpiryEffectiveDate,
  validateNextScheduledSynchronizerUpgrade,
  validateSummary,
  validateUrl,
} from './formValidators';

export type SetDsoConfigCompleteFormData = {
  common: CommonProposalFormData;
  config: ConfigFormData;
};

const createProposalAction = createProposalActions.find(a => a.value === 'SRARC_SetConfig');

export const SetDsoConfigRulesForm: () => JSX.Element = () => {
  const dsoInfoQuery = useDsoInfos();
  const dsoProposalsQuery = useListDsoRulesVoteRequests();
  const votesHooks = useVotesHooks();
  const pendingConfigFields = useMemo(
    () => buildPendingConfigFields(dsoProposalsQuery.data),
    [dsoProposalsQuery.data]
  );
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
        acc[field.fieldName] = {
          fieldName: field.fieldName,
          value: field.currentValue,
        };
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
      onChange: ({ value: formData }) => {
        const expiryError = validateExpiryEffectiveDate({
          expiration: formData.common.expiryDate,
          effectiveDate: formData.common.effectiveDate.effectiveDate,
        });

        if (expiryError) return expiryError;

        const syncUpgradeTime = formData.config.nextScheduledSynchronizerUpgradeTime.value;
        const syncMigrationId = formData.config.nextScheduledSynchronizerUpgradeMigrationId.value;
        const effectiveDate = formData.common.effectiveDate.effectiveDate;

        return validateNextScheduledSynchronizerUpgrade(
          syncUpgradeTime,
          syncMigrationId,
          effectiveDate
        );
      },
      onSubmit: ({ value: formData }) => {
        const changes = configFormDataToConfigChanges(formData.config, dsoConfigChanges);

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

  const maybeConfig = dsoInfoQuery.data?.dsoRules.payload.config;
  const dsoConfig = maybeConfig ? maybeConfig : null;
  // passing the config twice here because we initially have no changes
  const dsoConfigChanges = buildDsoConfigChanges(dsoConfig, dsoConfig, true);

  const effectiveDateString = form.state.values.common.effectiveDate.effectiveDate;
  const effectivity = effectiveDateString ? dayjs(effectiveDateString).toDate() : undefined;

  const changes = configFormDataToConfigChanges(form.state.values.config, dsoConfigChanges, false);
  const changedFields = changes.filter(c => c.currentValue !== c.newValue);

  const hasChangedFields = changedFields.length > 0;

  const baseConfig = dsoConfig;
  const newConfig = buildDsoRulesConfigFromChanges(changes);
  const dsoAction: DsoRules_ActionRequiringConfirmation = {
    tag: 'SRARC_SetConfig',
    value: {
      baseConfig: baseConfig,
      newConfig: newConfig,
    },
  };
  const dsoConfigToCompareWith = getDsoConfigToCompareWith(
    effectivity,
    undefined,
    votesHooks,
    dsoAction,
    dsoInfoQuery
  );

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
          configFormData={changedFields}
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
                id="set-dso-config-rules-action"
                muiTextFieldProps={{ disabled: true }}
              />
            )}
          </form.AppField>

          <form.AppField name="common.expiryDate">
            {field => (
              <field.DateField
                title="Threshold Deadline"
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
                {field => (
                  <field.ConfigField
                    configChange={change}
                    key={index}
                    pendingFieldInfo={pendingConfigFields.find(
                      f => f.fieldName === change.fieldName
                    )}
                    effectiveDate={form.state.values.common.effectiveDate.effectiveDate}
                  />
                )}
              </form.AppField>
            ))}
          </Box>
        </>
      )}

      <JsonDiffAccordion>
        {dsoConfigToCompareWith[1] && hasChangedFields ? (
          <PrettyJsonDiff
            changes={{
              newConfig: dsoAction.value.newConfig,
              baseConfig: dsoAction.value.baseConfig || dsoConfigToCompareWith[1],
              actualConfig: dsoConfigToCompareWith[1],
            }}
          />
        ) : (
          <Typography>No changes</Typography>
        )}
      </JsonDiffAccordion>

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
