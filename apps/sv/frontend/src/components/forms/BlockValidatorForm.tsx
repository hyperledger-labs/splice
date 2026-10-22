// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Checkbox, FormControlLabel, FormGroup, Typography } from '@mui/material';
import { ActionRequiringConfirmation } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { useAppForm } from '../../hooks/form';
import { useDsoInfos } from '../../contexts/SvContext';
import dayjs from 'dayjs';
import { createProposalActions, getInitialExpiration } from '../../utils/governance';
import { dateTimeFormatISO } from '@canton-network/splice-common-frontend-utils';
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
import { BlockValidatorFormData } from '../../utils/types';
import { EffectiveDateField } from '../form-components/EffectiveDateField';
import { ProposalSummary } from '../governance/ProposalSummary';
import { ProposalSubmissionError } from '../form-components/ProposalSubmissionError';
import { useProposalMutation } from '../../hooks/useProposalMutation';
import {
  CREATE_PROPOSAL_LABEL_EFFECTIVE_AT,
  CREATE_PROPOSAL_LABEL_PROPOSAL_SUMMARY,
  CREATE_PROPOSAL_LABEL_PROPOSAL_TYPE,
  CREATE_PROPOSAL_LABEL_SUPPORTING_URL,
  CREATE_PROPOSAL_LABEL_THRESHOLD_DEADLINE,
  SUPPORTING_URL_PLACEHOLDER,
  THRESHOLD_DEADLINE_SUBTITLE,
} from '../../utils/constants';

export const BlockValidatorForm: React.FC = () => {
  const dsoInfosQuery = useDsoInfos();
  const initialExpiration = getInitialExpiration(dsoInfosQuery.data);
  const initialEffectiveDate = dayjs(initialExpiration).add(1, 'day');
  const [showConfirmation, setShowConfirmation] = useState(false);
  const mutation = useProposalMutation();

  const createProposalAction = createProposalActions.find(a => a.value === 'SRARC_BlockValidator');

  const defaultValues: BlockValidatorFormData = {
    action: createProposalAction?.name || '',
    expiryDate: initialExpiration.format(dateTimeFormatISO),
    effectiveDate: {
      type: 'custom',
      effectiveDate: initialEffectiveDate.format(dateTimeFormatISO),
    },
    url: '',
    summary: '',
    participantId: '',
    revoked: false,
    loginAfter: dayjs().add(10, 'day').format(dateTimeFormatISO),
  };

  const form = useAppForm({
    defaultValues,
    onSubmit: async ({ value }) => {
      const action: ActionRequiringConfirmation = {
        tag: 'ARC_DsoRules',
        value: {
          dsoAction: {
            tag: 'SRARC_BlockValidator',
            value: {
              participantId: value.participantId,
              revoked: value.revoked,
              loginAfter: value.revoked ? null : dayjs(value.loginAfter).toISOString(),
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
      <FormLayout
        form={form}
        id="block-validator-form"
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
            formType="block-validator"
            participantId={form.state.values.participantId}
            revoked={form.state.values.revoked}
            loginAfter={form.state.values.revoked ? undefined : form.state.values.loginAfter}
            onEdit={() => setShowConfirmation(false)}
            onSubmit={() => {}}
          />
        ) : (
          <>
            <form.AppField name="action">
              {field => (
                <field.ProposalTypeField
                  id="block-validator-action"
                  title={CREATE_PROPOSAL_LABEL_PROPOSAL_TYPE}
                />
              )}
            </form.AppField>

            <form.AppField
              name="participantId"
              validators={{
                onBlur: ({ value }) => validateParticipantId(value),
                onChange: ({ value }) => validateParticipantId(value),
              }}
            >
              {field => (
                <field.TextField
                  title="Participant ID"
                  id="block-validator-participant-id"
                  muiTextFieldProps={{ placeholder: 'Enter Participant ID' }}
                />
              )}
            </form.AppField>

            <form.Field name="revoked">
              {field => (
                <FormGroup sx={{ mb: 3 }}>
                  <FormControlLabel
                    control={
                      <Checkbox
                        checked={field.state.value}
                        onChange={e => field.handleChange(e.target.checked)}
                        id="block-validator-revoked"
                      />
                    }
                    label={<Typography fontWeight="bold">Permanent Revocation</Typography>}
                  />
                  <Typography variant="body2" color="text.secondary" sx={{ ml: 4, mt: -0.5 }}>
                    If checked, the validator needs an sv vote to unblocked.
                  </Typography>
                </FormGroup>
              )}
            </form.Field>

            <form.Subscribe
              selector={state => state.values.revoked}
              children={revoked =>
                !revoked ? (
                  <form.AppField name="loginAfter">
                    {field => (
                      <field.DateField
                        title="Login After"
                        description="The date and time after which the validator can log in again."
                        id="block-validator-login-after"
                      />
                    )}
                  </form.AppField>
                ) : null
              }
            />

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
                  id="block-validator-expiry-date"
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
                  id="block-validator-effective-date"
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
                  id="block-validator-summary"
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
                  id="block-validator-url"
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
