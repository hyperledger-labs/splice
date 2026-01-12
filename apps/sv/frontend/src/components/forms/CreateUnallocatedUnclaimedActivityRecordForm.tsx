// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import type { ActionRequiringConfirmation } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { dateTimeFormatISO } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import dayjs from 'dayjs';
import { useState } from 'react';
import { useDsoInfos } from '../../contexts/SvContext';
import { useAppForm } from '../../hooks/form';
import { useProposalMutation } from '../../hooks/useProposalMutation';
import { THRESHOLD_DEADLINE_SUBTITLE } from '../../utils/constants';
import { createProposalActions, getInitialExpiration } from '../../utils/governance';
import type { CommonProposalFormData } from '../../utils/types';
import { EffectiveDateField } from '../form-components/EffectiveDateField';
import { ProposalSubmissionError } from '../form-components/ProposalSubmissionError';
import { ProposalSummary } from '../governance/ProposalSummary';
import { FormLayout } from './FormLayout';
import {
  validateEffectiveDate,
  validateExpiration,
  validateExpiryEffectiveDate,
  // validateMintedBeneficiary,
  validateMintBefore,
  validateRewardAmount,
  validateSummary,
  validateUrl,
  validateMintBeforeAndEffectiveDate,
} from './formValidators';

interface ExtraFormField {
  beneficiary: string;
  amount: string;
  mintBefore: string;
}

export type CreateUnallocatedUnclaimedActivityRecordFormData = CommonProposalFormData &
  ExtraFormField;

export const CreateUnallocatedUnclaimedActivityRecordForm: React.FC = _ => {
  const dsoInfosQuery = useDsoInfos();
  const initialExpiration = getInitialExpiration(dsoInfosQuery.data);
  const initialEffectiveDate = dayjs(initialExpiration).add(1, 'day');
  const [showConfirmation, setShowConfirmation] = useState(false);
  const mutation = useProposalMutation();

  const createProposalAction = createProposalActions.find(
    a => a.value === 'SRARC_CreateUnallocatedUnclaimedActivityRecord'
  );

  const defaultValues: CreateUnallocatedUnclaimedActivityRecordFormData = {
    action: createProposalAction?.name || '',
    expiryDate: initialExpiration.format(dateTimeFormatISO),
    effectiveDate: {
      type: 'custom',
      effectiveDate: initialEffectiveDate.format(dateTimeFormatISO),
    },
    url: '',
    summary: '',
    beneficiary: '',
    amount: '',
    mintBefore: initialEffectiveDate.add(2, 'day').format(dateTimeFormatISO),
  };

  const form = useAppForm({
    defaultValues,
    onSubmit: async ({ value: formData }) => {
      const action: ActionRequiringConfirmation = {
        tag: 'ARC_DsoRules',
        value: {
          dsoAction: {
            tag: 'SRARC_CreateUnallocatedUnclaimedActivityRecord',
            value: {
              beneficiary: formData.beneficiary,
              amount: formData.amount,
              reason: formData.summary,
              expiresAt: dayjs(formData.mintBefore).toISOString(),
            },
          },
        },
      };

      if (!showConfirmation) {
        setShowConfirmation(true);
      } else {
        await mutation.mutateAsync({ formData, action }).catch(e => {
          console.error(`Failed to submit proposal`, e);
        });
      }
    },

    validators: {
      onChange: ({ value: formData }) => {
        const expiryError = validateExpiryEffectiveDate({
          expiration: formData.expiryDate,
          effectiveDate: formData.effectiveDate.effectiveDate,
        });

        if (expiryError) return expiryError;

        return validateMintBeforeAndEffectiveDate({
          effectiveDate: formData.effectiveDate.effectiveDate,
          mintBefore: formData.mintBefore,
        });
      },
    },
  });

  return (
    <>
      <FormLayout form={form} id="create-unallocated-unclaimed-activity-record-form">
        {showConfirmation ? (
          <ProposalSummary
            actionName={form.state.values.action}
            url={form.state.values.url}
            summary={form.state.values.summary}
            expiryDate={form.state.values.expiryDate}
            effectiveDate={form.state.values.effectiveDate.effectiveDate}
            formType="create-unallocated-unclaimed-activity-record"
            amount={form.state.values.amount}
            beneficiary={form.state.values.beneficiary}
            expiresAt={form.state.values.mintBefore}
            onEdit={() => setShowConfirmation(false)}
            onSubmit={() => {}}
          />
        ) : (
          <>
            <form.AppField name="action">
              {field => (
                <field.TextField
                  title="Action"
                  id="create-unallocated-unclaimed-activity-record-action"
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
                  id="create-unallocated-unclaimed-activity-record-expiry-date"
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
                  id="create-unallocated-unclaimed-activity-record-effective-date"
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
                <field.ProposalSummaryField id="create-unallocated-unclaimed-activity-record-summary" />
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
                  title="URL"
                  id="create-unallocated-unclaimed-activity-record-url"
                />
              )}
            </form.AppField>

            <form.AppField
              name="beneficiary"
              validators={
                {
                  // onBlur: ({ value }) => validateMintedBeneficiary(value),
                  // onChange: ({ value }) => validateMintedBeneficiary(value),
                }
              }
            >
              {field => (
                <field.TextField
                  title="Beneficiary"
                  id="create-unallocated-unclaimed-activity-record-beneficiary"
                />
              )}
            </form.AppField>

            <form.AppField
              name="amount"
              validators={{
                onBlur: ({ value }) => validateRewardAmount(value),
                onChange: ({ value }) => validateRewardAmount(value),
              }}
            >
              {field => (
                <field.TextField
                  title="Amount"
                  id="create-unallocated-unclaimed-activity-record-amount"
                />
              )}
            </form.AppField>

            <form.AppField
              name="mintBefore"
              validators={{
                onChange: ({ value }) => validateMintBefore(value),
                onBlur: ({ value }) => validateMintBefore(value),
              }}
            >
              {field => (
                <field.DateField
                  title="Must Mint Before"
                  id="create-unallocated-unclaimed-activity-record-mint-before"
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
