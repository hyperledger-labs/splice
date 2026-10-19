// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { ActionRequiringConfirmation } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { useSearchParams } from 'react-router';
import { useDsoInfos } from '../../contexts/SvContext';
import dayjs from 'dayjs';
import {
  activityWeightToOptional,
  createProposalActions,
  getInitialExpiration,
} from '../../utils/governance';
import { dateTimeFormatISO } from '@canton-network/splice-common-frontend-utils';
import { useAppForm } from '../../hooks/form';
import { useStore } from '@tanstack/react-form';
import {
  CREATE_PROPOSAL_LABEL_EFFECTIVE_AT,
  CREATE_PROPOSAL_LABEL_FEATURED_APP_CONTRACT_ID,
  CREATE_PROPOSAL_LABEL_PROPOSAL_SUMMARY,
  CREATE_PROPOSAL_LABEL_PROPOSAL_TYPE,
  CREATE_PROPOSAL_LABEL_PROVIDER_PARTY_ID,
  CREATE_PROPOSAL_LABEL_SUPPORTING_URL,
  CREATE_PROPOSAL_LABEL_THRESHOLD_DEADLINE,
  SUPPORTING_URL_PLACEHOLDER,
  THRESHOLD_DEADLINE_SUBTITLE,
} from '../../utils/constants';
import { CommonProposalFormData } from '../../utils/types';
import { ContractId } from '@daml/types';
import { FeaturedAppRight } from '@daml.js/splice-amulet/lib/Splice/Amulet';
import {
  validateActivityWeight,
  validateEffectiveDate,
  validateExpiration,
  validateExpiryEffectiveDate,
  validatePartyId,
  validateSummary,
  validateUrl,
} from './formValidators';
import { FormLayout } from './FormLayout';
import { EffectiveDateField } from '../form-components/EffectiveDateField';
import { useEffect, useState } from 'react';
import { ProposalSummary } from '../governance/ProposalSummary';
import { ProposalSubmissionError } from '../form-components/ProposalSubmissionError';
import { useProposalMutation } from '../../hooks/useProposalMutation';
import { useSvAdminClient } from '../../contexts/SvAdminServiceContext';
import { useFeaturedAppRightPicker } from '../../hooks/useFeaturedAppRightPicker';

type ProviderId = string;
type FeaturedAppRightId = string;

interface ExtraFormField {
  idValue: ProviderId;
  partyId: ProviderId;
  rightCid: FeaturedAppRightId;
  activityWeight: string;
}

export type GrantRevokeFeaturedAppFormData = CommonProposalFormData & ExtraFormField;

const GRANT_REVOKE_FEATURED_APP_CONFIG = {
  SRARC_GrantFeaturedAppRight: {
    providerFieldTitle: CREATE_PROPOSAL_LABEL_PROVIDER_PARTY_ID,
    testIdPrefix: 'grant-featured-app',
    reviewFormKey: 'grant-right' as const,
  },
  SRARC_RevokeFeaturedAppRight: {
    providerFieldTitle: CREATE_PROPOSAL_LABEL_PROVIDER_PARTY_ID,
    rightCidFieldTitle: CREATE_PROPOSAL_LABEL_FEATURED_APP_CONTRACT_ID,
    testIdPrefix: 'revoke-featured-app',
    reviewFormKey: 'revoke-right' as const,
  },
} as const;

export type GrantRevokeFeaturedAppActions = keyof typeof GRANT_REVOKE_FEATURED_APP_CONFIG;

export interface GrantRevokeFeaturedAppFormProps {
  selectedAction: GrantRevokeFeaturedAppActions;
}

export const GrantRevokeFeaturedAppForm: React.FC<GrantRevokeFeaturedAppFormProps> = props => {
  const { selectedAction } = props;
  const svAdminClient = useSvAdminClient();
  const dsoInfosQuery = useDsoInfos();
  const initialExpiration = getInitialExpiration(dsoInfosQuery.data);
  const initialEffectiveDate = dayjs(initialExpiration).add(1, 'day');
  const [showConfirmation, setShowConfirmation] = useState(false);
  const picker = useFeaturedAppRightPicker(svAdminClient);
  const mutation = useProposalMutation();

  // TODO(#1819): use either search params or props and not both.
  const formAction: GrantRevokeFeaturedAppActions =
    (useSearchParams()[0]?.get('action') as GrantRevokeFeaturedAppActions) || selectedAction;

  const { providerFieldTitle, testIdPrefix, reviewFormKey } =
    GRANT_REVOKE_FEATURED_APP_CONFIG[formAction];
  const rightCidFieldTitle =
    formAction === 'SRARC_RevokeFeaturedAppRight'
      ? GRANT_REVOKE_FEATURED_APP_CONFIG.SRARC_RevokeFeaturedAppRight.rightCidFieldTitle
      : undefined;
  const createProposalAction = createProposalActions.find(a => a.value === formAction);

  const validateGrantProviderExists = async (value: string) => {
    if (formAction !== 'SRARC_GrantFeaturedAppRight') return undefined;
    if (validatePartyId(value)) return undefined;

    try {
      await svAdminClient.getPartyToParticipant(value);
      return undefined;
    } catch {
      return 'Provider party not found on ledger';
    }
  };

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
    partyId: '',
    rightCid: '',
    activityWeight: '',
  };

  const form = useAppForm({
    defaultValues,

    onSubmit: async ({ value }) => {
      const actionMap: Record<
        GrantRevokeFeaturedAppActions,
        (formValues: GrantRevokeFeaturedAppFormData) => ActionRequiringConfirmation
      > = {
        SRARC_GrantFeaturedAppRight: formValues => ({
          tag: 'ARC_DsoRules',
          value: {
            dsoAction: {
              tag: 'SRARC_GrantFeaturedAppRight',
              value: {
                provider: formValues.idValue,
                activityWeight: activityWeightToOptional(formValues.activityWeight),
                config: null,
              },
            },
          },
        }),
        SRARC_RevokeFeaturedAppRight: formValues => ({
          tag: 'ARC_DsoRules',
          value: {
            dsoAction: {
              tag: 'SRARC_RevokeFeaturedAppRight',
              value: { rightCid: formValues.rightCid as ContractId<FeaturedAppRight> },
            },
          },
        }),
      };

      const action = actionMap[formAction](value);

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

  useEffect(() => {
    if (formAction !== 'SRARC_RevokeFeaturedAppRight') return;

    const currentRightCid = form.state.values.rightCid;
    const hasSelectedOption = picker.rightOptions.some(option => option.value === currentRightCid);
    if (hasSelectedOption) return;

    const nextRightCid = picker.rightOptions.length === 1 ? picker.rightOptions[0].value : '';
    form.setFieldValue('rightCid', nextRightCid);
  }, [form, formAction, picker.rightOptions]);

  const partyId = useStore(form.store, state => state.values.partyId);
  const providerHasNoRights =
    picker.providerSearched && picker.rightOptions.length === 0 && !validatePartyId(partyId);

  return (
    <>
      <FormLayout
        form={form}
        id={`${testIdPrefix}-form`}
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
            formType={reviewFormKey}
            grantRight={form.state.values.idValue}
            activityWeight={form.state.values.activityWeight}
            providerPartyId={form.state.values.partyId}
            revokeRight={form.state.values.rightCid}
            onEdit={() => setShowConfirmation(false)}
            onSubmit={() => {}}
          />
        ) : (
          <>
            <form.AppField name="action">
              {field => (
                <field.ProposalTypeField
                  id={`${testIdPrefix}-action`}
                  title={CREATE_PROPOSAL_LABEL_PROPOSAL_TYPE}
                />
              )}
            </form.AppField>

            {formAction === 'SRARC_GrantFeaturedAppRight' && (
              <form.AppField
                name="idValue"
                validators={{
                  onBlur: ({ value }) => validatePartyId(value),
                  onChange: ({ value }) => validatePartyId(value),
                  onChangeAsyncDebounceMs: 500,
                  onChangeAsync: ({ value }) => validateGrantProviderExists(value),
                  onBlurAsync: ({ value }) => validateGrantProviderExists(value),
                }}
              >
                {field => (
                  <field.TextField
                    title={providerFieldTitle}
                    id={`${testIdPrefix}-idValue`}
                    scrollableIdentifier
                    subtitle={field.state.meta.isValidating ? 'Validating provider...' : undefined}
                  />
                )}
              </form.AppField>
            )}

            {formAction === 'SRARC_GrantFeaturedAppRight' && (
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
                    id={`${testIdPrefix}-activityWeight`}
                    subtitle="Optional. Leave blank to use the default weight"
                  />
                )}
              </form.AppField>
            )}

            {formAction === 'SRARC_RevokeFeaturedAppRight' && (
              <>
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
                      title={providerFieldTitle}
                      id={`${testIdPrefix}-partyId`}
                      scrollableIdentifier
                      subtitle={
                        field.state.meta.isValidating
                          ? 'Loading Featured Application Contract IDs...'
                          : undefined
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
                      title={rightCidFieldTitle!}
                      id={`${testIdPrefix}-rightCid`}
                      options={picker.rightOptions}
                      scrollableIdentifier
                      disabled={picker.rightOptions.length === 0}
                      placeholder={
                        providerHasNoRights
                          ? 'No Featured Application Contract IDs found for this provider'
                          : undefined
                      }
                    />
                  )}
                </form.AppField>
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
                  title={CREATE_PROPOSAL_LABEL_EFFECTIVE_AT}
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
              {field => (
                <field.ProposalSummaryField
                  id={`${testIdPrefix}-summary`}
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
                  id={`${testIdPrefix}-url`}
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
    </>
  );
};
