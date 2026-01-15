// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { ActionRequiringConfirmation } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { useSearchParams } from 'react-router-dom';
import { useDsoInfos } from '../../contexts/SvContext';
import dayjs from 'dayjs';
import { createProposalActions, getInitialExpiration } from '../../utils/governance';
import { dateTimeFormatISO } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useAppForm } from '../../hooks/form';
import { THRESHOLD_DEADLINE_SUBTITLE } from '../../utils/constants';
import { CommonProposalFormData, SupportedActionTag } from '../../utils/types';
import { ContractId } from '@daml/types';
import { FeaturedAppRight } from '@daml.js/splice-amulet/lib/Splice/Amulet';
import {
  validateEffectiveDate,
  validateExpiration,
  validateExpiryEffectiveDate,
  validateGrantRevokeFeaturedAppRight,
  validateSummary,
  validateUrl,
} from './formValidators';
import { FormLayout } from './FormLayout';
import { EffectiveDateField } from '../form-components/EffectiveDateField';
import { useState } from 'react';
import { ProposalSummary } from '../governance/ProposalSummary';
import { ProposalSubmissionError } from '../form-components/ProposalSubmissionError';
import { useProposalMutation } from '../../hooks/useProposalMutation';

type ProviderId = string;
type FeaturedAppRightId = string;

interface ExtraFormField {
  idValue: ProviderId | FeaturedAppRightId;
}

export type GrantRevokeFeaturedAppFormData = CommonProposalFormData & ExtraFormField;

export interface GrantRevokeFeaturedAppFormProps {
  selectedAction: GrantRevokeFeaturedAppActions;
}

export type GrantRevokeFeaturedAppActions = Extract<
  SupportedActionTag,
  'SRARC_GrantFeaturedAppRight' | 'SRARC_RevokeFeaturedAppRight'
>;

export const GrantRevokeFeaturedAppForm: React.FC<GrantRevokeFeaturedAppFormProps> = props => {
  const { selectedAction } = props;
  const dsoInfosQuery = useDsoInfos();
  const initialExpiration = getInitialExpiration(dsoInfosQuery.data);
  const initialEffectiveDate = dayjs(initialExpiration).add(1, 'day');
  const [showConfirmation, setShowConfirmation] = useState(false);
  const mutation = useProposalMutation();

  // TODO(#1819): use either search params or props and not both.
  const formAction: GrantRevokeFeaturedAppActions =
    (useSearchParams()[0]?.get('action') as GrantRevokeFeaturedAppActions) || selectedAction;
  const keysMap = {
    SRARC_GrantFeaturedAppRight: {
      idValueFieldTitle: 'Provider',
      testIdPrefix: 'grant-featured-app',
      reviewFormKey: 'grant-right' as const,
    },
    SRARC_RevokeFeaturedAppRight: {
      idValueFieldTitle: 'Featured Application Right Contract Id',
      testIdPrefix: 'revoke-featured-app',
      reviewFormKey: 'revoke-right' as const,
    },
  };

  const { idValueFieldTitle, testIdPrefix, reviewFormKey } = keysMap[formAction];
  const createProposalAction = createProposalActions.find(a => a.value === formAction);

  const defaultValues: GrantRevokeFeaturedAppFormData = {
    action: createProposalAction?.name || '',
    expiryDate: initialExpiration.format(dateTimeFormatISO),
    effectiveDate: {
      type: 'custom',
      effectiveDate: initialEffectiveDate.format(dateTimeFormatISO),
    },
    url: '',
    summary: '',
    idValue: '',
  };

  const form = useAppForm({
    defaultValues,

    onSubmit: async ({ value }) => {
      const actionMap: Record<
        'SRARC_GrantFeaturedAppRight' | 'SRARC_RevokeFeaturedAppRight',
        (idValue: string) => ActionRequiringConfirmation
      > = {
        SRARC_GrantFeaturedAppRight: (idValue: string) => ({
          tag: 'ARC_DsoRules',
          value: {
            dsoAction: {
              tag: 'SRARC_GrantFeaturedAppRight',
              value: { provider: idValue },
            },
          },
        }),
        SRARC_RevokeFeaturedAppRight: (idValue: string) => ({
          tag: 'ARC_DsoRules',
          value: {
            dsoAction: {
              tag: 'SRARC_RevokeFeaturedAppRight',
              value: { rightCid: idValue as ContractId<FeaturedAppRight> },
            },
          },
        }),
      };

      const action = actionMap[formAction](value.idValue);

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
      <FormLayout form={form} id={`${testIdPrefix}-form`}>
        {showConfirmation ? (
          <ProposalSummary
            actionName={form.state.values.action}
            url={form.state.values.url}
            summary={form.state.values.summary}
            expiryDate={form.state.values.expiryDate}
            effectiveDate={form.state.values.effectiveDate.effectiveDate}
            formType={reviewFormKey}
            grantRight={form.state.values.idValue}
            revokeRight={form.state.values.idValue}
            onEdit={() => setShowConfirmation(false)}
            onSubmit={() => {}}
          />
        ) : (
          <>
            <form.AppField name="action">
              {field => (
                <field.TextField
                  title="Action"
                  id={`${testIdPrefix}-action`}
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
                  id={`${testIdPrefix}-expiry-date`}
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
                  id={`${testIdPrefix}-effective-date`}
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
              {field => <field.ProposalSummaryField id={`${testIdPrefix}-summary`} />}
            </form.AppField>

            <form.AppField
              name="url"
              validators={{
                onBlur: ({ value }) => validateUrl(value),
                onChange: ({ value }) => validateUrl(value),
              }}
            >
              {field => <field.TextField title="URL" id={`${testIdPrefix}-url`} />}
            </form.AppField>

            <form.AppField
              name="idValue"
              validators={{
                onBlur: ({ value }) => validateGrantRevokeFeaturedAppRight(value),
                onChange: ({ value }) => validateGrantRevokeFeaturedAppRight(value),
              }}
            >
              {field => (
                <field.TextField title={idValueFieldTitle} id={`${testIdPrefix}-idValue`} />
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
