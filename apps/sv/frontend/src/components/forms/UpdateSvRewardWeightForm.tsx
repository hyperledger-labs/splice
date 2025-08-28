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
import { createProposalActions, getInitialExpiration } from '../../utils/governance';
import { EffectiveDateField } from '../form-components/EffectiveDateField';
import { CommonProposalFormData } from '../../utils/types';
import { ConfirmProposal } from '../governance/ConfirmProposal';

interface ExtraFormField {
  sv: string;
  weight: string;
}

type UpdateSvRewardWeightFormData = CommonProposalFormData & ExtraFormField;

interface UpdateSvRewardWeightFormProps {
  onSubmit: (
    data: UpdateSvRewardWeightFormData,
    action: ActionRequiringConfirmation
  ) => Promise<void>;
}

export const UpdateSvRewardWeightForm: React.FC<UpdateSvRewardWeightFormProps> = props => {
  const { onSubmit } = props;
  const dsoInfosQuery = useDsoInfos();
  const initialExpiration = getInitialExpiration(dsoInfosQuery.data);
  const initialEffectiveDate = dayjs(initialExpiration).add(1, 'day');
  const [showConfirmation, setShowConfirmation] = useState(false);

  const svs = useMemo(
    () => dsoInfosQuery.data?.dsoRules.payload.svs.entriesArray() || [],
    [dsoInfosQuery]
  );

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

    onSubmit: ({ value }) => {
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
        console.log('submit sv reward weight form data: ', value, 'with action:', action);
        onSubmit(value, action);
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
          <ConfirmProposal
            actionName={form.state.values.action}
            url={form.state.values.url}
            summary={form.state.values.summary}
            expiryDate={form.state.values.expiryDate}
            effectiveDate={form.state.values.effectiveDate.effectiveDate}
            formType="sv-reward-weight"
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
          <form.FormErrors />
          <form.FormControls
            showConfirmation={showConfirmation}
            onEdit={() => setShowConfirmation(false)}
          />
        </form.AppForm>
      </FormLayout>
      {/* } */}
    </>
  );
};
