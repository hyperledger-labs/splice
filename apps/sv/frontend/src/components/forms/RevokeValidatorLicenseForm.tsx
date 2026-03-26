// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { ActionRequiringConfirmation } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { ValidatorLicense } from '@daml.js/splice-amulet/lib/Splice/ValidatorLicense/module';
import { ContractId } from '@daml/types';
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
  validateUrl,
} from './formValidators';
import { FormLayout } from './FormLayout';
import { useMemo, useState } from 'react';
import { CommonProposalFormData } from '../../utils/types';
import { EffectiveDateField } from '../form-components/EffectiveDateField';
import { ProposalSummary } from '../governance/ProposalSummary';
import { ProposalSubmissionError } from '../form-components/ProposalSubmissionError';
import { useProposalMutation } from '../../hooks/useProposalMutation';
import { THRESHOLD_DEADLINE_SUBTITLE } from '../../utils/constants';
import { useValidatorLicenses } from '../../hooks/useValidatorLicenses';

interface ExtraFormFields {
  validator: string;
  reason: string;
}

export type RevokeValidatorLicenseFormData = CommonProposalFormData & ExtraFormFields;

export const RevokeValidatorLicenseForm: React.FC = _ => {
  const dsoInfosQuery = useDsoInfos();
  const initialExpiration = getInitialExpiration(dsoInfosQuery.data);
  const initialEffectiveDate = dayjs(initialExpiration).add(1, 'day');
  const [showConfirmation, setShowConfirmation] = useState(false);
  const mutation = useProposalMutation();
  const validatorLicensesQuery = useValidatorLicenses(1000);

  const licenses = useMemo(() => {
    if (!validatorLicensesQuery.data) return [];
    return validatorLicensesQuery.data.pages.flatMap(page => page.validatorLicenses);
  }, [validatorLicensesQuery.data]);

  const validatorOptions: { key: string; value: string }[] = useMemo(
    () => licenses.map(l => ({ key: l.payload.validator, value: l.payload.validator })),
    [licenses]
  );

  const createProposalAction = createProposalActions.find(
    a => a.value === 'VLRARC_WithdrawValidatorLicense'
  );

  const defaultValues: RevokeValidatorLicenseFormData = {
    action: createProposalAction?.name || '',
    expiryDate: initialExpiration.format(dateTimeFormatISO),
    effectiveDate: {
      type: 'custom',
      effectiveDate: initialEffectiveDate.format(dateTimeFormatISO),
    },
    url: '',
    summary: '',
    validator: '',
    reason: '',
  };

  const form = useAppForm({
    defaultValues,
    onSubmit: async ({ value }) => {
      const license = licenses.find(l => l.payload.validator === value.validator);
      if (!license) return;

      const action: ActionRequiringConfirmation = {
        tag: 'ARC_ValidatorLicense',
        value: {
          validatorLicenseCid: license.contractId as ContractId<ValidatorLicense>,
          validatorLicenseAction: {
            tag: 'VLRARC_WithdrawValidatorLicense',
            value: { reason: value.reason },
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
        return validateExpiryEffectiveDate({
          expiration: value.expiryDate,
          effectiveDate: value.effectiveDate.effectiveDate,
        });
      },
    },
  });

  return (
    <>
      <FormLayout form={form} id="revoke-validator-license-form">
        {showConfirmation ? (
          <ProposalSummary
            actionName={form.state.values.action}
            url={form.state.values.url}
            summary={form.state.values.summary}
            expiryDate={form.state.values.expiryDate}
            effectiveDate={form.state.values.effectiveDate.effectiveDate}
            formType="revoke-validator-license"
            validator={form.state.values.validator}
            reason={form.state.values.reason}
            onEdit={() => setShowConfirmation(false)}
            onSubmit={() => {}}
          />
        ) : (
          <>
            <form.AppField name="action">
              {field => (
                <field.TextField
                  title="Action"
                  id="revoke-validator-license-action"
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
                  title="Threshold Deadline"
                  description={THRESHOLD_DEADLINE_SUBTITLE}
                  id="revoke-validator-license-expiry-date"
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
                  id="revoke-validator-license-effective-date"
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
              {field => <field.ProposalSummaryField id="revoke-validator-license-summary" />}
            </form.AppField>

            <form.AppField
              name="url"
              validators={{
                onBlur: ({ value }) => validateUrl(value),
                onChange: ({ value }) => validateUrl(value),
              }}
            >
              {field => <field.TextField title="URL" id="revoke-validator-license-url" />}
            </form.AppField>

            <form.AppField
              name="validator"
              validators={{
                onBlur: ({ value }) =>
                  !value ? 'Please select a validator' : false,
                onChange: ({ value }) =>
                  !value ? 'Please select a validator' : false,
              }}
            >
              {field => (
                <field.SelectField
                  title="Validator"
                  options={validatorOptions}
                  id="revoke-validator-license-validator"
                />
              )}
            </form.AppField>

            <form.AppField
              name="reason"
              validators={{
                onBlur: ({ value }) =>
                  !value ? 'Please provide a reason' : false,
                onChange: ({ value }) =>
                  !value ? 'Please provide a reason' : false,
              }}
            >
              {field => (
                <field.TextField
                  title="Reason"
                  id="revoke-validator-license-reason"
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
    </>
  );
};
