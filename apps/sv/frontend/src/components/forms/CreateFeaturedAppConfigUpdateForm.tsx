// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import React, { useEffect, useRef, useState } from 'react';
import dayjs from 'dayjs';
import { useStore } from '@tanstack/react-form';
import { ActionRequiringConfirmation } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { FeaturedAppConfig } from '@daml.js/splice-amulet/lib/Splice/Amulet';
import { dateTimeFormatISO } from '@canton-network/splice-common-frontend-utils';
import { useSvAdminClient } from '../../contexts/SvAdminServiceContext';
import { useDsoInfos } from '../../contexts/SvContext';
import { useFeaturedAppRightPicker } from '../../hooks/useFeaturedAppRightPicker';
import { useProposalMutation } from '../../hooks/useProposalMutation';
import { useAppForm } from '../../hooks/form';
import { CreateFeaturedAppConfigUpdateFormData } from '../../utils/types';
import {
  activityWeightToOptional,
  appKindSelectOptions,
  appKindToOptional,
  buildFeaturedAppConfigUpdateEntry,
  createProposalActions,
  getInitialExpiration,
  opsPartiesToOptional,
} from '../../utils/governance';
import {
  validateActivityWeight,
  validateEffectiveDate,
  validateExpiration,
  validateExpiryEffectiveDate,
  validateOpsParties,
  validatePartyId,
  validateSummary,
  validateUrl,
} from './formValidators';
import { FormLayout } from './FormLayout';
import { EffectiveDateField } from '../form-components/EffectiveDateField';
import { OpsPartiesField } from '../form-components/OpsPartiesField';
import { ProposalSummary } from '../governance/ProposalSummary';
import { ProposalSubmissionError } from '../form-components/ProposalSubmissionError';
import {
  CREATE_PROPOSAL_LABEL_THRESHOLD_DEADLINE,
  SUPPORTING_URL_PLACEHOLDER,
  THRESHOLD_DEADLINE_SUBTITLE,
} from '../../utils/constants';
import { useFeaturedAppRightConfig } from '../../hooks/useFeaturedAppRightConfig';
import { Alert } from '@mui/material';

export const CreateFeaturedAppConfigUpdateForm: React.FC = () => {
  const svAdminClient = useSvAdminClient();
  const dsoInfosQuery = useDsoInfos();
  const initialExpiration = getInitialExpiration(dsoInfosQuery.data);
  const initialEffectiveDate = dayjs(initialExpiration).add(1, 'day');
  const picker = useFeaturedAppRightPicker(svAdminClient);
  const [showConfirmation, setShowConfirmation] = useState(false);
  const mutation = useProposalMutation();
  const idPrefix = 'amend-featured-app';

  const createProposalAction = createProposalActions.find(
    a => a.value === 'SRARC_CreateFeaturedAppConfigUpdateInstruction'
  );

  // Latest fetched current config, read at submit time as the verbatim amend baseConfig.
  // A ref (not the query directly) keeps onSubmit from referencing the query before its
  // declaration and stays current across renders.
  const baseConfigRef = useRef<FeaturedAppConfig | null>(null);

  const defaultValues: CreateFeaturedAppConfigUpdateFormData = {
    action: createProposalAction?.name || '',
    expiryDate: initialExpiration.format(dateTimeFormatISO),
    effectiveDate: {
      type: 'custom',
      effectiveDate: initialEffectiveDate.format(dateTimeFormatISO),
    },
    url: '',
    summary: '',
    partyId: '',
    rightCid: '',
    appKind: '',
    activityWeight: '',
    opsParties: [],
  };

  const form = useAppForm({
    defaultValues,
    onSubmit: async ({ value }) => {
      // baseConfig must be the provider's CURRENT config, verbatim (None stays None), so
      // DAML patchScalar treats untouched fields as no-ops.
      const baseConfig = baseConfigRef.current;
      if (!baseConfig) return;

      const newConfig: FeaturedAppConfig = {
        appKind: appKindToOptional(value.appKind),
        activityWeight: activityWeightToOptional(value.activityWeight),
        opsParties: opsPartiesToOptional(value.opsParties),
      };

      const action: ActionRequiringConfirmation = {
        tag: 'ARC_DsoRules',
        value: {
          dsoAction: {
            tag: 'SRARC_CreateFeaturedAppConfigUpdateInstruction',
            value: { updates: [{ _1: value.partyId, _2: { baseConfig, newConfig } }] },
          },
        },
      };

      if (!showConfirmation) setShowConfirmation(true);
      else
        await mutation.mutateAsync({ formData: value, action }).catch(e => {
          console.error('Failed to submit proposal', e);
        });
    },
    validators: {
      onChange: ({ value }) =>
        validateExpiryEffectiveDate({
          expiration: value.expiryDate,
          effectiveDate: value.effectiveDate.effectiveDate,
        }),
    },
  });

  const partyId = useStore(form.store, state => state.values.partyId);
  const rightCid = useStore(form.store, state => state.values.rightCid);

  // Fetch the selected right's full current config (appKind / activityWeight / opsParties)
  // to prepopulate the editor and serve as the amend baseConfig.
  const currentConfigQuery = useFeaturedAppRightConfig(rightCid);

  useEffect(() => {
    baseConfigRef.current = currentConfigQuery.data ?? null;
  }, [currentConfigQuery.data]);

  // Auto-select the only right, or clear the selection when the provider changes.
  useEffect(() => {
    const hasSelectedOption = picker.rightOptions.some(o => o.value === rightCid);
    if (hasSelectedOption) return;
    const nextRightCid = picker.rightOptions.length === 1 ? picker.rightOptions[0].value : '';
    form.setFieldValue('rightCid', nextRightCid);
  }, [form, picker.rightOptions, rightCid]);

  // Prepopulate the editable fields from the current config once per selected right.
  const [prepopulatedCid, setPrepopulatedCid] = useState<string>('');
  useEffect(() => {
    const cfg = currentConfigQuery.data;
    if (!rightCid || !cfg || prepopulatedCid === rightCid) return;
    setPrepopulatedCid(rightCid);
    form.setFieldValue('appKind', cfg.appKind ?? '');
    form.setFieldValue('activityWeight', cfg.activityWeight ?? '');
    form.setFieldValue('opsParties', cfg.opsParties ?? []);
  }, [form, rightCid, currentConfigQuery.data, prepopulatedCid]);

  const providerHasNoRights =
    picker.providerSearched && picker.rightOptions.length === 0 && !validatePartyId(partyId);

  const reviewEntry = currentConfigQuery.data
    ? buildFeaturedAppConfigUpdateEntry(form.state.values.partyId, currentConfigQuery.data, {
        appKind: appKindToOptional(form.state.values.appKind),
        activityWeight: activityWeightToOptional(form.state.values.activityWeight),
        opsParties: opsPartiesToOptional(form.state.values.opsParties),
      })
    : undefined;

  const configNotReady = !!rightCid && (currentConfigQuery.isError || !currentConfigQuery.data);

  return (
    <>
      <FormLayout
        form={form}
        id={`${idPrefix}-form`}
        actionName={form.state.values.action}
        isReviewStep={showConfirmation}
      >
        {showConfirmation ? (
          <ProposalSummary
            actionName={form.state.values.action}
            url={form.state.values.url}
            summary={form.state.values.summary}
            expiryDate={form.state.values.expiryDate}
            effectiveDate={form.state.values.effectiveDate.effectiveDate}
            formType="amend-featured-app"
            providerPartyId={form.state.values.partyId}
            rightCid={form.state.values.rightCid}
            scalarChanges={reviewEntry?.scalarChanges ?? []}
            opsAdded={reviewEntry?.opsAdded ?? []}
            opsRemoved={reviewEntry?.opsRemoved ?? []}
            onEdit={() => setShowConfirmation(false)}
            onSubmit={() => {}}
          />
        ) : (
          <>
            <form.AppField name="action">
              {field => <field.ProposalTypeField id={`${idPrefix}-action`} />}
            </form.AppField>

            <form.AppField
              name="partyId"
              validators={{
                onChange: ({ value }) => validatePartyId(value),
                onChangeAsyncDebounceMs: 500,
                onChangeAsync: ({ value }) => picker.loadFeaturedAppRightsAndValidate(value),
              }}
            >
              {field => (
                <field.TextField
                  title="Provider Party ID"
                  id={`${idPrefix}-partyId`}
                  scrollableIdentifier
                  subtitle={
                    field.state.meta.isValidating ? 'Loading featured app rights...' : undefined
                  }
                  onChange={() => {
                    picker.resetOptions();
                  }}
                />
              )}
            </form.AppField>

            <form.AppField
              name="rightCid"
              validators={{
                onBlur: ({ value }) => picker.validateRightSelection(value),
                onChange: ({ value }) => picker.validateRightSelection(value),
              }}
            >
              {field => (
                <field.SelectField
                  title="Featured Application Contract ID"
                  id={`${idPrefix}-rightCid`}
                  options={picker.rightOptions}
                  scrollableIdentifier
                  disabled={picker.rightOptions.length === 0}
                  placeholder={
                    providerHasNoRights
                      ? 'No featured application rights found for this provider'
                      : undefined
                  }
                />
              )}
            </form.AppField>

            {currentConfigQuery.isError && (
              <Alert severity="error" data-testid={`${idPrefix}-config-load-error`}>
                Could not load the current configuration for the selected right. Reload and try
                again.
              </Alert>
            )}

            <form.AppField name="appKind">
              {field => (
                <field.SelectField
                  title="App Kind"
                  id={`${idPrefix}-appKind`}
                  options={appKindSelectOptions()}
                />
              )}
            </form.AppField>

            <form.AppField
              name="activityWeight"
              validators={{
                onBlur: ({ value }) => validateActivityWeight(value),
                onChange: ({ value }) => validateActivityWeight(value),
              }}
            >
              {field => (
                <field.TextField
                  title="Activity Weight"
                  id={`${idPrefix}-activityWeight`}
                  subtitle="Optional. Leave blank to use the default weight"
                />
              )}
            </form.AppField>

            <form.Field
              name="opsParties"
              validators={{
                // No onChangeListenTo on partyId: it has an async validator, and re-validating
                // opsParties on every partyId change prevents canSubmit from settling.
                // provider-not-in-opsParties is still enforced on submit.
                onChange: ({ value }) => validateOpsParties(value, form.state.values.partyId),
              }}
            >
              {field => (
                <OpsPartiesField
                  id={`${idPrefix}-opsParties`}
                  title="Ops Parties"
                  subtitle="Optional. Parties whose submissions count as this app's activity."
                  value={field.state.value}
                  onChange={field.handleChange}
                  onBlur={field.handleBlur}
                  error={field.state.meta.errors?.[0]}
                />
              )}
            </form.Field>

            <form.AppField
              name="expiryDate"
              validators={{
                onChange: ({ value }) => validateExpiration(value),
                onBlur: ({ value }) => validateExpiration(value),
              }}
            >
              {field => (
                <field.DateField
                  title={CREATE_PROPOSAL_LABEL_THRESHOLD_DEADLINE}
                  description={THRESHOLD_DEADLINE_SUBTITLE}
                  id={`${idPrefix}-expiry-date`}
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
                  initialEffectiveDate={initialEffectiveDate.format(dateTimeFormatISO)}
                  id={`${idPrefix}-effective-date`}
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
              {field => <field.ProposalSummaryField id={`${idPrefix}-summary`} />}
            </form.AppField>

            <form.AppField
              name="url"
              validators={{
                onBlur: ({ value }) => validateUrl(value),
                onChange: ({ value }) => validateUrl(value),
              }}
            >
              {field => (
                <field.TextField
                  title="URL"
                  id={`${idPrefix}-url`}
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
            disabled={configNotReady}
          />
        </form.AppForm>
      </FormLayout>
    </>
  );
};
