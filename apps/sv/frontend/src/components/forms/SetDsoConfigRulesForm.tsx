// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import type {
  ActionRequiringConfirmation,
  DsoRules_ActionRequiringConfirmation,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import {
  getDsoConfigToCompareWith,
  PrettyJsonDiff,
  useVotesHooks,
} from '@canton-network/splice-common-frontend';
import { dateTimeFormatISO } from '@canton-network/splice-common-frontend-utils';
import { Alert, Box, Typography } from '@mui/material';
import dayjs from 'dayjs';
import { useMemo, useState } from 'react';
import { useDsoInfos } from '../../contexts/SvContext';
import { useListDsoRulesVoteRequests } from '../../hooks';
import { useAppForm } from '../../hooks/form';
import { useProposalMutation } from '../../hooks/useProposalMutation';
import { buildDsoConfigChanges } from '../../utils/buildDsoConfigChanges';
import { buildDsoRulesConfigFromChanges } from '../../utils/buildDsoRulesConfigFromChanges';
import {
  CREATE_PROPOSAL_CONFIG_ROW_DIVIDER_GAP,
  CREATE_PROPOSAL_CONFIG_ROW_GAP,
  CREATE_PROPOSAL_FIELD_LABEL_SX,
} from '../../constants/createProposalLayout';
import {
  CREATE_PROPOSAL_LABEL_CONFIGURATION,
  CREATE_PROPOSAL_LABEL_EFFECTIVE_AT,
  CREATE_PROPOSAL_LABEL_PROPOSAL_SUMMARY,
  CREATE_PROPOSAL_LABEL_PROPOSAL_TYPE,
  CREATE_PROPOSAL_LABEL_SUPPORTING_URL,
  CREATE_PROPOSAL_LABEL_THRESHOLD_DEADLINE,
  SUPPORTING_URL_PLACEHOLDER,
  THRESHOLD_DEADLINE_SUBTITLE,
} from '../../utils/constants';
import {
  buildPendingConfigFields,
  configFormDataToConfigChanges,
  createProposalActions,
  getInitialExpiration,
  withSwitchOverConfigValue,
} from '../../utils/governance';
import type { CommonProposalFormData, ConfigFormData } from '../../utils/types';
import { EffectiveDateField } from '../form-components/EffectiveDateField';
import { ProposalSubmissionError } from '../form-components/ProposalSubmissionError';
import { JsonDiffAccordion } from '../governance/JsonDiffAccordion';
import { ProposalSummary } from '../governance/ProposalSummary';
import { FormLayout } from './FormLayout';
import {
  validateEffectiveDate,
  validateExpiration,
  validateExpiryEffectiveDate,
  validateNextScheduledSynchronizerUpgrade,
  validateNextScheduledLogicalSynchronizerUpgrade,
  validateSummary,
  validateUrl,
  SwitchOverEntry,
} from './formValidators';
import { SwitchOverTimesField } from '../form-components/SwitchOverTimesField';

export type SetDsoConfigCompleteFormData = {
  common: CommonProposalFormData;
  config: ConfigFormData;
  switchOverTimes: {
    entries: SwitchOverEntry[];
    allowNonFutureDated: boolean;
  };
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
  const maybeConfig = dsoInfoQuery.data?.dsoRules.payload.config;
  const dsoConfig = maybeConfig ? maybeConfig : null;

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
        switchOverTimes: {
          entries: Object.entries(dsoConfig?.svOperationsSwitchOverTimes ?? {}).map(
            ([key, time]) => ({ key, time })
          ),
          allowNonFutureDated: false,
        },
      };
    }

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
      switchOverTimes: {
        entries: Object.entries(dsoConfig?.svOperationsSwitchOverTimes ?? {}).map(
          ([key, time]) => ({ key, time })
        ),
        allowNonFutureDated: false,
      },
    };
  }, [dsoInfoQuery.data, initialExpiration, initialEffectiveDate, dsoConfig]);

  const form = useAppForm({
    defaultValues,
    onSubmit: async ({ value: formData }) => {
      if (!showConfirmation) {
        setShowConfirmation(true);
      } else {
        const changes = configFormDataToConfigChanges(
          withSwitchOverConfigValue(
            formData.config,
            'svOperationsSwitchOverTimes',
            formData.switchOverTimes.entries
          ),
          dsoConfigChanges,
          false
        );
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

        // The config fields are populated from dsoInfo, which may not have loaded yet
        const syncUpgradeTime = formData.config.nextScheduledSynchronizerUpgradeTime?.value ?? '';
        const syncMigrationId =
          formData.config.nextScheduledSynchronizerUpgradeMigrationId?.value ?? '';
        const effectiveDate = formData.common.effectiveDate.effectiveDate;
        const currentValue = (fieldName: string) => defaultValues.config[fieldName]?.value ?? '';

        const synchronizerUpgradeError = validateNextScheduledSynchronizerUpgrade(
          syncUpgradeTime,
          syncMigrationId,
          effectiveDate,
          {
            upgradeTime: currentValue('nextScheduledSynchronizerUpgradeTime'),
            migrationId: currentValue('nextScheduledSynchronizerUpgradeMigrationId'),
          }
        );
        if (synchronizerUpgradeError) return synchronizerUpgradeError;
        const logicalSynchronizerUpgradeError = validateNextScheduledLogicalSynchronizerUpgrade(
          formData.config.nextScheduledLogicalSynchronizerUpgradeTopologyFreezeTime?.value ?? '',
          formData.config.nextScheduledLogicalSynchronizerUpgradeUpgradeTime?.value ?? '',
          formData.config.nextScheduledLogicalSynchronizerUpgradeNewPhysicalSynchronizerSerial
            ?.value ?? '',
          formData.config
            .nextScheduledLogicalSynchronizerUpgradeNewPhysicalSynchronizerProtocolVersion?.value ??
            '',
          effectiveDate,
          {
            topologyFreezeTime: currentValue(
              'nextScheduledLogicalSynchronizerUpgradeTopologyFreezeTime'
            ),
            upgradeTime: currentValue('nextScheduledLogicalSynchronizerUpgradeUpgradeTime'),
            newPhysicalSynchronizerSerial: currentValue(
              'nextScheduledLogicalSynchronizerUpgradeNewPhysicalSynchronizerSerial'
            ),
            newPhysicalSynchronizerProtocolVersion: currentValue(
              'nextScheduledLogicalSynchronizerUpgradeNewPhysicalSynchronizerProtocolVersion'
            ),
          }
        );
        if (logicalSynchronizerUpgradeError) return logicalSynchronizerUpgradeError;
        return false;
      },
      onSubmit: ({ value: formData }) => {
        const changes = configFormDataToConfigChanges(
          withSwitchOverConfigValue(
            formData.config,
            'svOperationsSwitchOverTimes',
            formData.switchOverTimes.entries
          ),
          dsoConfigChanges
        );

        if (changes.length === 0) {
          return 'Cannot submit a proposal with no configuration changes';
        }

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

  // passing the config twice here because we initially have no changes
  const dsoConfigChanges = buildDsoConfigChanges(dsoConfig, dsoConfig, true);

  const effectiveDateString = form.state.values.common.effectiveDate.effectiveDate;
  const effectivity = effectiveDateString ? dayjs(effectiveDateString).toDate() : undefined;

  const changes = configFormDataToConfigChanges(
    withSwitchOverConfigValue(
      form.state.values.config,
      'svOperationsSwitchOverTimes',
      form.state.values.switchOverTimes.entries
    ),
    dsoConfigChanges,
    false
  );
  const changedFields = changes.filter(c => c.currentValue !== c.newValue);

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

  const jsonDiffContent = dsoConfigToCompareWith[1] ? (
    <PrettyJsonDiff
      changes={{
        newConfig: dsoAction.value.newConfig,
        baseConfig: dsoAction.value.baseConfig || dsoConfigToCompareWith[1],
        actualConfig: dsoConfigToCompareWith[1],
      }}
    />
  ) : null;

  return (
    <FormLayout
      form={form}
      id="set-dso-config-rules-form"
      actionName={form.state.values.common.action}
      isReviewStep={showConfirmation}
    >
      {showConfirmation ? (
        <ProposalSummary
          actionName={form.state.values.common.action}
          url={form.state.values.common.url}
          summary={form.state.values.common.summary}
          expiryDate={form.state.values.common.expiryDate}
          effectiveDate={form.state.values.common.effectiveDate.effectiveDate}
          formType="config-change"
          configFormData={changedFields}
          jsonDiff={<JsonDiffAccordion variant="review">{jsonDiffContent}</JsonDiffAccordion>}
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
              <field.ProposalTypeField
                id="set-dso-config-rules-action"
                title={CREATE_PROPOSAL_LABEL_PROPOSAL_TYPE}
              />
            )}
          </form.AppField>

          <Box
            sx={{ display: 'flex', flexDirection: 'column', gap: CREATE_PROPOSAL_CONFIG_ROW_GAP }}
          >
            <Typography component="p" sx={{ ...CREATE_PROPOSAL_FIELD_LABEL_SX, mb: 0 }}>
              {CREATE_PROPOSAL_LABEL_CONFIGURATION}
            </Typography>

            {dsoConfigChanges
              .filter(change => change.fieldName !== 'svOperationsSwitchOverTimes')
              .map(change => (
                <form.AppField name={`config.${change.fieldName}`} key={change.fieldName}>
                  {field => (
                    <Box
                      sx={{
                        display: 'flex',
                        flexDirection: 'column',
                        gap: CREATE_PROPOSAL_CONFIG_ROW_DIVIDER_GAP,
                      }}
                    >
                      <field.ConfigField
                        configChange={change}
                        pendingFieldInfo={pendingConfigFields.find(
                          f => f.fieldName === change.fieldName
                        )}
                        effectiveDate={form.state.values.common.effectiveDate.effectiveDate}
                      />
                    </Box>
                  )}
                </form.AppField>
              ))}

            <SwitchOverTimesField
              form={form}
              title="SV operations switch-over times"
              effectiveDate={form.state.values.common.effectiveDate.effectiveDate}
              currentEntries={defaultValues.switchOverTimes.entries}
            />

            <JsonDiffAccordion variant="form">{jsonDiffContent}</JsonDiffAccordion>
          </Box>

          <form.AppField
            name="common.expiryDate"
            validators={{
              onChange: ({ value }) => validateExpiration(value),
              onBlur: ({ value }) => validateExpiration(value),
            }}
          >
            {field => (
              <field.DateField
                title={CREATE_PROPOSAL_LABEL_THRESHOLD_DEADLINE}
                description={THRESHOLD_DEADLINE_SUBTITLE}
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
                title={CREATE_PROPOSAL_LABEL_EFFECTIVE_AT}
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
            {field => (
              <field.ProposalSummaryField
                id="set-dso-config-rules-summary"
                title={CREATE_PROPOSAL_LABEL_PROPOSAL_SUMMARY}
              />
            )}
          </form.AppField>

          <form.AppField
            name="common.url"
            validators={{
              onBlur: ({ value }) => validateUrl(value),
              onChange: ({ value }) => validateUrl(value),
            }}
          >
            {field => (
              <field.TextField
                title={CREATE_PROPOSAL_LABEL_SUPPORTING_URL}
                id="set-dso-config-rules-url"
                muiTextFieldProps={{ placeholder: SUPPORTING_URL_PLACEHOLDER }}
              />
            )}
          </form.AppField>
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
