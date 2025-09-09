// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import dayjs from 'dayjs';
import { useAppForm } from '../../hooks/form';
import { dateTimeFormatISO } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useDsoInfos } from '../../contexts/SvContext';
import { ActionRequiringConfirmation } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { FormLayout } from './FormLayout';
import { useMemo, useState } from 'react';
import {
  validateEffectiveDate,
  validateExpiration,
  validateExpiryEffectiveDate,
  validateSummary,
  validateSvSelection,
  validateUrl,
  validateWeight,
} from './formValidators';
import {
  createProposalActions,
  getInitialExpiration,
  getSvRewardWeight,
} from '../../utils/governance';
import { EffectiveDateField } from '../form-components/EffectiveDateField';
import { CommonProposalFormData } from '../../utils/types';
import { ProposalSummary } from '../governance/ProposalSummary';
import { ProposalSubmissionError } from '../form-components/ProposalSubmissionError';
import { useProposalMutation } from '../../hooks/useProposalMutation';

interface ExtraFormField {
  sv: string;
  weight: string;
}

export type UpdateSvRewardWeightFormData = CommonProposalFormData & ExtraFormField;

export const UpdateSvRewardWeightForm: React.FC = _ => {
  const dsoInfosQuery = useDsoInfos();
  const initialExpiration = getInitialExpiration(dsoInfosQuery.data);
  const initialEffectiveDate = dayjs(initialExpiration).add(1, 'day');
  const [showConfirmation, setShowConfirmation] = useState(false);
  const mutation = useProposalMutation();

  const svs = useMemo(
    () => dsoInfosQuery.data?.dsoRules.payload.svs.entriesArray() || [],
    [dsoInfosQuery]
  );

  const svPartyId = dsoInfosQuery.data?.svPartyId || '';
  const currentWeight = getSvRewardWeight(svs, svPartyId);

  const svOptions: { key: string; value: string }[] = useMemo(
    () => svs.map(([partyId, svInfo]) => ({ key: svInfo.name, value: partyId })),
    [svs]
  );

  const createProposalAction = createProposalActions.find(
    a => a.value === 'SRARC_UpdateSvRewardWeight'
  );

  const defaultValues: UpdateSvRewardWeightFormData = {
    action: createProposalAction?.name || '',
    expiryDate: initialExpiration.format(dateTimeFormatISO),
    effectiveDate: {
      type: 'custom',
      effectiveDate: initialEffectiveDate.format(dateTimeFormatISO),
    },
    url: '',
    summary: '',
    sv: '',
    weight: '',
  };

  const form = useAppForm({
    defaultValues,

    onSubmit: async ({ value }) => {
      const action: ActionRequiringConfirmation = {
        tag: 'ARC_DsoRules',
        value: {
          dsoAction: {
            tag: 'SRARC_UpdateSvRewardWeight',
            value: {
              svParty: value.sv,
              newRewardWeight: value.weight,
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
        return validateExpiryEffectiveDate({
          expiration: value.expiryDate,
          effectiveDate: value.effectiveDate.effectiveDate,
        });
      },
    },
  });

  return (
    <>
      <FormLayout form={form} id="update-sv-reward-weight-form">
        {showConfirmation ? (
          <ProposalSummary
            actionName={form.state.values.action}
            url={form.state.values.url}
            summary={form.state.values.summary}
            expiryDate={form.state.values.expiryDate}
            effectiveDate={form.state.values.effectiveDate.effectiveDate}
            formType="sv-reward-weight"
            currentWeight={currentWeight}
            svRewardWeightMember={form.state.values.sv}
            svRewardWeight={form.state.values.weight}
            onEdit={() => setShowConfirmation(false)}
            onSubmit={() => {}}
          />
        ) : (
          <>
            <form.AppField name="action">
              {field => (
                <field.TextField
                  title="Action"
                  id="update-sv-reward-weight-action"
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
                  id="update-sv-reward-weight-expiry-date"
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
                  id="update-sv-reward-weight-effective-date"
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
                <field.TextArea title="Proposal Summary" id="update-sv-reward-weight-summary" />
              )}
            </form.AppField>

            <form.AppField
              name="url"
              validators={{
                onBlur: ({ value }) => validateUrl(value),
                onChange: ({ value }) => validateUrl(value),
              }}
            >
              {field => <field.TextField title="URL" id="update-sv-reward-weight-url" />}
            </form.AppField>

            <form.AppField
              name="sv"
              validators={{
                onBlur: ({ value }) => validateSvSelection(value),
                onChange: ({ value }) => validateSvSelection(value),
              }}
            >
              {field => (
                <field.SelectField
                  title="Member"
                  options={svOptions}
                  id="update-sv-reward-weight-member"
                />
              )}
            </form.AppField>

            <form.AppField
              name="weight"
              validators={{
                onBlur: ({ value }) => validateWeight(value),
                onChange: ({ value }) => validateWeight(value),
              }}
            >
              {field => <field.TextField title="Weight" id="update-sv-reward-weight-weight" />}
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
