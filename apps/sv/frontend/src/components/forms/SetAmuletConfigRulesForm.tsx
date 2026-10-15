// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import {
  ActionRequiringConfirmation,
  AmuletRules_ActionRequiringConfirmation,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
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
  buildAmuletRulesPendingConfigFields,
  configFormDataToConfigChanges,
  createProposalActions,
  getInitialExpiration,
  withSwitchOverConfigValue,
} from '../../utils/governance';
import { CommonProposalFormData, ConfigFormData } from '../../utils/types';
import dayjs from 'dayjs';
import { useDsoInfos } from '../../contexts/SvContext';
import { useMemo, useState } from 'react';
import { dateTimeFormatISO } from '@canton-network/splice-common-frontend-utils';
import { buildAmuletConfigChanges } from '../../utils/buildAmuletConfigChanges';
import { useAppForm } from '../../hooks/form';
import {
  SwitchOverEntry,
  validateEffectiveDate,
  validateExpiration,
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
import {
  getAmuletConfigToCompareWith,
  PrettyJsonDiff,
  useVotesHooks,
} from '@canton-network/splice-common-frontend';
import { JsonDiffAccordion } from '../governance/JsonDiffAccordion';
import { SwitchOverTimesField } from '../form-components/SwitchOverTimesField';

export type SetAmuletConfigCompleteFormData = {
  common: CommonProposalFormData;
  config: ConfigFormData;
  switchOverTimes: {
    entries: SwitchOverEntry[];
    allowNonFutureDated: boolean;
  };
};

const createProposalAction = createProposalActions.find(a => a.value === 'CRARC_SetConfig');

export const SetAmuletConfigRulesForm: () => JSX.Element = () => {
  const dsoInfoQuery = useDsoInfos();
  const mutation = useProposalMutation();
  const dsoProposalsQuery = useListDsoRulesVoteRequests();
  const votesHooks = useVotesHooks();
  const initialExpiration = getInitialExpiration(dsoInfoQuery.data);
  const initialEffectiveDate = dayjs(initialExpiration).add(1, 'day');
  const [showConfirmation, setShowConfirmation] = useState(false);
  const pendingConfigFields = useMemo(
    () => buildAmuletRulesPendingConfigFields(dsoProposalsQuery.data),
    [dsoProposalsQuery.data]
  );
  const maybeConfig = dsoInfoQuery.data?.amuletRules.payload.configSchedule.initialValue;
  const amuletConfig = maybeConfig ? maybeConfig : null;

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
        switchOverTimes: {
          entries: Object.entries(amuletConfig?.amuletSwitchOverTimes ?? {}).map(([key, time]) => ({
            key,
            time,
          })),
          allowNonFutureDated: false,
        },
      };
    }

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
      switchOverTimes: {
        entries: Object.entries(amuletConfig?.amuletSwitchOverTimes ?? {}).map(([key, time]) => ({
          key,
          time,
        })),
        allowNonFutureDated: false,
      },
    };
  }, [dsoInfoQuery.data, initialExpiration, initialEffectiveDate, amuletConfig]);

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
          withSwitchOverConfigValue(
            formData.config,
            'amuletSwitchOverTimes',
            formData.switchOverTimes.entries
          ),
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
        const changes = configFormDataToConfigChanges(
          withSwitchOverConfigValue(
            formData.config,
            'amuletSwitchOverTimes',
            formData.switchOverTimes.entries
          ),
          allAmuletConfigChanges
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
  const allAmuletConfigChanges = buildAmuletConfigChanges(amuletConfig, amuletConfig, true);

  const effectiveDateString = form.state.values.common.effectiveDate.effectiveDate;
  const effectivity = effectiveDateString ? dayjs(effectiveDateString).toDate() : undefined;

  const changes = configFormDataToConfigChanges(
    withSwitchOverConfigValue(
      form.state.values.config,
      'amuletSwitchOverTimes',
      form.state.values.switchOverTimes.entries
    ),
    allAmuletConfigChanges,
    false
  );

  const baseConfig = amuletConfig;
  const newConfig = buildAmuletRulesConfigFromChanges(changes);
  const dsoAction: AmuletRules_ActionRequiringConfirmation = {
    tag: 'CRARC_SetConfig',
    value: {
      baseConfig: baseConfig!,
      newConfig: newConfig,
    },
  };

  const amuletConfigToCompareWith = getAmuletConfigToCompareWith(
    effectivity,
    undefined,
    votesHooks,
    dsoAction,
    dsoInfoQuery
  );

  const jsonDiffContent =
    amuletConfigToCompareWith && amuletConfigToCompareWith[1] ? (
      <PrettyJsonDiff
        changes={{
          newConfig: dsoAction.value.newConfig,
          baseConfig: dsoAction.value.baseConfig || amuletConfigToCompareWith[1],
          actualConfig: amuletConfigToCompareWith[1],
        }}
      />
    ) : null;

  return (
    <FormLayout
      form={form}
      id="set-amulet-config-rules-form"
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
          configFormData={configFormDataToConfigChanges(
            withSwitchOverConfigValue(
              form.state.values.config,
              'amuletSwitchOverTimes',
              form.state.values.switchOverTimes.entries
            ),
            allAmuletConfigChanges
          )}
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
                id="set-amulet-config-rules-action"
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

            {allAmuletConfigChanges
              .filter(change => change.fieldName !== 'amuletSwitchOverTimes')
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
                      />
                    </Box>
                  )}
                </form.AppField>
              ))}

            <SwitchOverTimesField
              form={form}
              title="Amulet switch-over times"
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
                title={CREATE_PROPOSAL_LABEL_EFFECTIVE_AT}
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
              <field.ProposalSummaryField
                id="set-amulet-config-rules-summary"
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
                id="set-amulet-config-rules-url"
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
