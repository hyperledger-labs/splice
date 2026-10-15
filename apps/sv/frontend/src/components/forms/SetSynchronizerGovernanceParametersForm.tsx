// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import dayjs from 'dayjs';
import { useEffect, useRef, useState } from 'react';
import { Alert } from '@mui/material';
import { useStore } from '@tanstack/react-form';
import { dateTimeFormatISO } from '@canton-network/splice-common-frontend-utils';
import { ContractId } from '@daml/types';
import { RegisteredSynchronizer } from '@daml.js/splice-amulet/lib/Splice/DecentralizedSynchronizer';
import { ActionRequiringConfirmation } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';

import { useAppForm } from '../../hooks/form';
import { useDsoInfos } from '../../contexts/SvContext';
import { useProposalMutation } from '../../hooks/useProposalMutation';
import { useSynchronizerRegistrationPicker } from '../../hooks/useSynchronizerRegistrationPicker';
import { createProposalActions, getInitialExpiration } from '../../utils/governance';
import { CommonProposalFormData } from '../../utils/types';
import {
  CREATE_PROPOSAL_LABEL_EFFECTIVE_AT,
  CREATE_PROPOSAL_LABEL_PROPOSAL_SUMMARY,
  CREATE_PROPOSAL_LABEL_PROPOSAL_TYPE,
  CREATE_PROPOSAL_LABEL_SUPPORTING_URL,
  CREATE_PROPOSAL_LABEL_THRESHOLD_DEADLINE,
  SUPPORTING_URL_PLACEHOLDER,
  THRESHOLD_DEADLINE_SUBTITLE,
} from '../../utils/constants';
import {
  validateEffectiveDate,
  validateExpiration,
  validateExpiryEffectiveDate,
  validateSummary,
  validateSynchronizerId,
  validateUrl,
} from './formValidators';
import {
  fromGovernanceParameterValues,
  GovernanceParameterValues,
  governanceParameterFields,
  governanceParameterKeys,
  toGovernanceParameterValues,
} from './governanceParameterFields';
import { EffectiveDateField } from '../form-components/EffectiveDateField';
import { ProposalSubmissionError } from '../form-components/ProposalSubmissionError';
import { ProposalSummary } from '../governance/ProposalSummary';
import { FormLayout } from './FormLayout';
import { ResolvedSynchronizerRegistrationFields } from './ResolvedSynchronizerRegistration';

interface ExtraFormField {
  synchronizerId: string;
  registrationCid: string;
  governanceParameters: GovernanceParameterValues;
}

export type SetSynchronizerGovernanceParametersFormData = CommonProposalFormData & ExtraFormField;

const emptyGovernanceParameterValues = Object.fromEntries(
  governanceParameterKeys.map(key => [key, ''])
) as GovernanceParameterValues;

export const SetSynchronizerGovernanceParametersForm: React.FC = _ => {
  const dsoInfosQuery = useDsoInfos();
  const initialExpiration = getInitialExpiration(dsoInfosQuery.data);
  const initialEffectiveDate = dayjs(initialExpiration).add(1, 'day');
  const [showConfirmation, setShowConfirmation] = useState(false);
  const mutation = useProposalMutation();
  const picker = useSynchronizerRegistrationPicker();
  const idPrefix = 'set-synchronizer-governance-parameters';

  const createProposalAction = createProposalActions.find(
    a => a.value === 'SRARC_SetSynchronizerGovernanceParameters'
  );

  const defaultValues: SetSynchronizerGovernanceParametersFormData = {
    action: createProposalAction?.name || '',
    expiryDate: initialExpiration.format(dateTimeFormatISO),
    effectiveDate: {
      type: 'custom',
      effectiveDate: initialEffectiveDate.format(dateTimeFormatISO),
    },
    url: '',
    summary: '',
    synchronizerId: '',
    registrationCid: '',
    governanceParameters: emptyGovernanceParameterValues,
  };

  const form = useAppForm({
    defaultValues,

    onSubmit: async ({ value }) => {
      const resolved = picker.find(value.registrationCid);
      if (!resolved) return;
      const action: ActionRequiringConfirmation = {
        tag: 'ARC_DsoRules',
        value: {
          dsoAction: {
            tag: 'SRARC_SetSynchronizerGovernanceParameters',
            value: {
              registeredSynchronizerCid: resolved.contractId as ContractId<RegisteredSynchronizer>,
              setGovernanceParameters: {
                newGovernanceParameters: fromGovernanceParameterValues(value.governanceParameters),
              },
            },
          },
        },
      };

      if (!showConfirmation) {
        setShowConfirmation(true);
      } else {
        await mutation.mutateAsync({ formData: value, action }).catch(e => {
          console.error(`Failed to submit proposal`, e);
        });
      }
    },

    validators: {
      onChange: ({ value }) => {
        const dateError = validateExpiryEffectiveDate({
          expiration: value.expiryDate,
          effectiveDate: value.effectiveDate.effectiveDate,
        });
        if (dateError) return dateError;

        const resolved = picker.find(value.registrationCid);
        if (!resolved) return undefined;
        const current = toGovernanceParameterValues(resolved.governanceParameters);
        const proposed = toGovernanceParameterValues(
          fromGovernanceParameterValues(value.governanceParameters)
        );
        const unchanged = governanceParameterKeys.every(key => current[key] === proposed[key]);
        return unchanged
          ? 'The proposed parameters are the current ones. Change at least one.'
          : undefined;
      },
    },
  });

  useEffect(() => {
    const current = form.state.values.registrationCid;
    if (picker.options.some(option => option.value === current)) return;
    const next = picker.options.length === 1 ? picker.options[0].value : '';
    if (next !== current) form.setFieldValue('registrationCid', next);
  }, [form, picker.options]);

  const registrationCid = useStore(form.store, state => state.values.registrationCid);
  const resolved = picker.find(registrationCid);
  const prefilledFor = useRef<string | undefined>(undefined);
  useEffect(() => {
    if (!resolved || prefilledFor.current === resolved.contractId) return;
    prefilledFor.current = resolved.contractId;
    const current = toGovernanceParameterValues(resolved.governanceParameters);
    governanceParameterKeys.forEach(key =>
      form.setFieldValue(`governanceParameters.${key}`, current[key])
    );
  }, [form, resolved]);

  return (
    <FormLayout
      form={form}
      id={`${idPrefix}-form`}
      actionName={form.state.values.action}
      isReviewStep={showConfirmation}
    >
      {showConfirmation && resolved ? (
        <ProposalSummary
          actionName={form.state.values.action}
          url={form.state.values.url}
          summary={form.state.values.summary}
          expiryDate={form.state.values.expiryDate}
          effectiveDate={form.state.values.effectiveDate.effectiveDate}
          formType="set-synchronizer-governance-parameters"
          registration={resolved}
          newGovernanceParameters={fromGovernanceParameterValues(
            form.state.values.governanceParameters
          )}
          onEdit={() => setShowConfirmation(false)}
          onSubmit={() => {}}
        />
      ) : (
        <>
          <form.AppField name="action">
            {field => (
              <field.ProposalTypeField
                id={`${idPrefix}-action`}
                title={CREATE_PROPOSAL_LABEL_PROPOSAL_TYPE}
              />
            )}
          </form.AppField>

          <form.AppField
            name="synchronizerId"
            validators={{
              onBlur: ({ value }) => validateSynchronizerId(value.trim()),
              onChange: ({ value }) => validateSynchronizerId(value.trim()),
              onChangeAsyncDebounceMs: 500,
              onChangeAsync: ({ value }) => picker.loadAndValidate(value),
              onBlurAsync: ({ value }) => picker.loadAndValidate(value),
            }}
          >
            {field => (
              <field.TextField
                title="Synchronizer ID"
                id={`${idPrefix}-synchronizer-id`}
                subtitle="The id of the dedicated synchronizer whose parameters to set, as name::fingerprint"
                onChange={() => picker.reset()}
              />
            )}
          </form.AppField>

          <form.AppField
            name="registrationCid"
            validators={{
              onBlur: ({ value }) => picker.validateSelection(value),
              onChange: ({ value }) => picker.validateSelection(value),
            }}
          >
            {field => (
              <field.SelectField
                title="Registration Contract ID"
                id={`${idPrefix}-contract-id`}
                options={picker.options}
                scrollableIdentifier
                disabled={picker.options.length === 0}
                placeholder={
                  picker.options.length === 0
                    ? 'Enter a synchronizer id to list its registrations'
                    : undefined
                }
              />
            )}
          </form.AppField>

          {picker.options.length > 1 && (
            <Alert
              severity="warning"
              variant="outlined"
              data-testid={`${idPrefix}-duplicates-warning`}
            >
              This synchronizer id has {picker.options.length} registrations. Scan resolves it to
              the one with the lowest contract id, and changing the parameters recreates the
              registration under a new contract id, which can change which one that is. Consider
              offboarding the duplicates first.
            </Alert>
          )}

          {resolved && (
            <>
              <ResolvedSynchronizerRegistrationFields
                id={`${idPrefix}-resolved`}
                registration={resolved}
                showParameters
              />

              {governanceParameterKeys.map(key => (
                <form.AppField
                  key={key}
                  name={`governanceParameters.${key}`}
                  validators={{
                    onBlur: ({ value }) => governanceParameterFields[key].validate(value),
                    onChange: ({ value }) => governanceParameterFields[key].validate(value),
                  }}
                >
                  {field => (
                    <field.TextField
                      title={`Proposed ${governanceParameterFields[key].label}`}
                      id={`${idPrefix}-${key}`}
                      subtitle={governanceParameterFields[key].subtitle}
                    />
                  )}
                </form.AppField>
              ))}
            </>
          )}

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
                title={CREATE_PROPOSAL_LABEL_EFFECTIVE_AT}
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
            {field => (
              <field.ProposalSummaryField
                id={`${idPrefix}-summary`}
                title={CREATE_PROPOSAL_LABEL_PROPOSAL_SUMMARY}
              />
            )}
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
                title={CREATE_PROPOSAL_LABEL_SUPPORTING_URL}
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
        />
      </form.AppForm>
    </FormLayout>
  );
};
