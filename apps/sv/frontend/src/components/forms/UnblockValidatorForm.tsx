// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { ActionRequiringConfirmation } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { useAppForm } from '../../hooks/form';
import { useDsoInfos } from '../../contexts/SvContext';
import dayjs from 'dayjs';
import { createProposalActions, getInitialExpiration } from '../../utils/governance';
import { dateTimeFormatISO } from '@canton-network/splice-common-frontend-utils';
import { ContractId } from '@daml/types';
import { ValidatorBlocked } from '@daml.js/splice-dso-governance/lib/Splice/ValidatorBlocked';
import {
  validateEffectiveDate,
  validateExpiration,
  validateExpiryEffectiveDate,
  validateSummary,
  validateUrl,
  validateParticipantId,
} from './formValidators';
import { FormLayout } from './FormLayout';
import { useState } from 'react';
import { UnblockValidatorFormData } from '../../utils/types';
import { EffectiveDateField } from '../form-components/EffectiveDateField';
import { ProposalSummary } from '../governance/ProposalSummary';
import { ProposalSubmissionError } from '../form-components/ProposalSubmissionError';
import { useProposalMutation } from '../../hooks/useProposalMutation';
import { useSvAdminClient } from '../../contexts/SvAdminServiceContext';
import {
  CREATE_PROPOSAL_LABEL_EFFECTIVE_AT,
  CREATE_PROPOSAL_LABEL_PROPOSAL_SUMMARY,
  CREATE_PROPOSAL_LABEL_PROPOSAL_TYPE,
  CREATE_PROPOSAL_LABEL_SUPPORTING_URL,
  CREATE_PROPOSAL_LABEL_THRESHOLD_DEADLINE,
  SUPPORTING_URL_PLACEHOLDER,
  THRESHOLD_DEADLINE_SUBTITLE,
} from '../../utils/constants';

export const UnblockValidatorForm: React.FC = () => {
  const dsoInfosQuery = useDsoInfos();
  const svAdminClient = useSvAdminClient();
  const initialExpiration = getInitialExpiration(dsoInfosQuery.data);
  const initialEffectiveDate = dayjs(initialExpiration).add(1, 'day');
  const [showConfirmation, setShowConfirmation] = useState(false);
  const mutation = useProposalMutation();

  const createProposalAction = createProposalActions.find(
    a => a.value === 'SRARC_UnblockValidator'
  );

  const defaultValues: UnblockValidatorFormData = {
    action: createProposalAction?.name || '',
    expiryDate: initialExpiration.format(dateTimeFormatISO),
    effectiveDate: {
      type: 'custom',
      effectiveDate: initialEffectiveDate.format(dateTimeFormatISO),
    },
    url: '',
    summary: '',
    participantId: '',
  };

  const validateParticipantAndContracts = async (value: string) => {
    const formatErr = validateParticipantId(value);
    if (formatErr) return formatErr;

    try {
      const response = await svAdminClient.listValidatorBlockeds(value);
      if (response.blockeds.length === 0) {
        return 'No blocked contracts found for this participant.';
      }
      if (response.blockeds.length > 1) {
        return 'Multiple blocked contracts found. Please wait for SV automation to merge them.';
      }
      return undefined;
    } catch (error: unknown) {
      if (error instanceof Error && error.name === 'AbortError') {
        throw error;
      }
      return 'Failed to verify participant ID on ledger.';
    }
  };

  const form = useAppForm({
    defaultValues,
    onSubmit: async ({ value }) => {
      if (!showConfirmation) {
        setShowConfirmation(true);
      } else {
        const blockedsRes = await svAdminClient.listValidatorBlockeds(value.participantId);

        if (blockedsRes.blockeds.length !== 1) {
          console.error('Submission blocked: Participant must have exactly 1 blocked contract.');
          return;
        }

        const action: ActionRequiringConfirmation = {
          tag: 'ARC_DsoRules',
          value: {
            dsoAction: {
              tag: 'SRARC_UnblockValidator',
              value: {
                validatorBlockedCid: blockedsRes.blockeds[0]
                  .contract_id as unknown as ContractId<ValidatorBlocked>,
              },
            },
          },
        };

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
      <FormLayout
        form={form}
        id="unblock-validator-form"
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
            formType="unblock-validator"
            participantId={form.state.values.participantId}
            onEdit={() => setShowConfirmation(false)}
            onSubmit={() => {}}
          />
        ) : (
          <>
            <form.AppField name="action">
              {field => (
                <field.ProposalTypeField
                  id="unblock-validator-action"
                  title={CREATE_PROPOSAL_LABEL_PROPOSAL_TYPE}
                />
              )}
            </form.AppField>

            <form.AppField
              name="participantId"
              validators={{
                onChange: ({ value }) => validateParticipantId(value),
                onChangeAsyncDebounceMs: 500,
                onChangeAsync: ({ value }) => validateParticipantAndContracts(value),
              }}
            >
              {field => (
                <field.TextField
                  title="Participant ID"
                  id="unblock-validator-participant-id"
                  muiTextFieldProps={{ placeholder: 'Enter Participant ID' }}
                  subtitle={
                    field.state.meta.isValidating ? 'Validating participant ID...' : undefined
                  }
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
                  title={CREATE_PROPOSAL_LABEL_THRESHOLD_DEADLINE}
                  description={THRESHOLD_DEADLINE_SUBTITLE}
                  id="unblock-validator-expiry-date"
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
                  description="Select the date and time the proposal will take effect"
                  initialEffectiveDate={initialEffectiveDate.format(dateTimeFormatISO)}
                  id="unblock-validator-effective-date"
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
                  id="unblock-validator-summary"
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
                  id="unblock-validator-url"
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
