// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Box, TextField, Typography } from '@mui/material';
import { ConfigChange } from '../../utils/types';
import { ConfigValuesChanges } from './ConfigValuesChanges';

interface BaseProposalSummaryProps {
  actionName: string;
  url: string;
  summary: string;
  expiryDate: string;
  effectiveDate: string | undefined;
  onEdit: () => void;
  onSubmit: () => void;
}

type ProposalSummaryProps = BaseProposalSummaryProps &
  (
    | {
        formType: 'sv-reward-weight';
        svRewardWeightMember: string;
        currentWeight: string;
        svRewardWeight: string;
      }
    | {
        formType: 'offboard';
        offboardMember: string;
      }
    | {
        formType: 'grant-right';
        grantRight: string;
      }
    | {
        formType: 'revoke-right';
        revokeRight: string;
      }
    | {
        formType: 'config-change';
        configFormData: ConfigChange[];
      }
  );

export const ProposalSummary: React.FC<ProposalSummaryProps> = props => {
  const { formType, actionName, url, summary, expiryDate, effectiveDate } = props;

  return (
    <>
      <Typography variant="h3" gutterBottom>
        Proposal Summary
      </Typography>

      <ProposalField id="action" title="Action" value={actionName} />

      <ProposalField id="url" title="URL" value={url} />

      <ProposalField id="summary" title="Summary" textArea value={summary} />

      <ProposalField
        id="expiryDate"
        title="Expiry Date"
        subtitle="This is the last day voters can vote on this proposal"
        value={expiryDate}
      />

      <ProposalField
        id="effectiveDate"
        title="Effective Date"
        value={effectiveDate ? effectiveDate : 'Threshold'}
      />

      {formType === 'sv-reward-weight' && (
        <>
          <ProposalField
            id="svRewardWeightMember"
            title="Member"
            value={props.svRewardWeightMember}
          />
          <ConfigValuesChanges
            changes={[
              {
                label: 'SV Reward Weight',
                fieldName: 'svRewardWeight',
                currentValue: props.currentWeight,
                newValue: props.svRewardWeight,
              },
            ]}
          />
        </>
      )}

      {formType === 'grant-right' && (
        <ProposalField id="grantRight" title="Provider" value={props.grantRight} />
      )}

      {formType === 'revoke-right' && (
        <ProposalField
          id="revokeRight"
          title="Featured Application Right Contract Id"
          value={props.revokeRight}
        />
      )}

      {formType === 'offboard' && (
        <ProposalField id="offboardMember" title="Offboard Member" value={props.offboardMember} />
      )}

      {formType === 'config-change' && <ConfigValuesChanges changes={props.configFormData} />}
    </>
  );
};

interface ProposalFieldProps {
  id: string;
  title: string;
  subtitle?: string;
  textArea?: boolean;
  value: string;
}

const ProposalField: React.FC<ProposalFieldProps> = props => {
  const { id, title, subtitle, textArea, value } = props;
  return (
    <Box sx={{ minWidth: '80%' }}>
      <Typography variant="h5" id={`${id}-title`} data-testid={`${id}-title`} gutterBottom>
        {title}
      </Typography>
      <Box>
        {subtitle && (
          <Typography
            variant="body2"
            id={`${id}-subtitle`}
            data-testid={`${id}-subtitle`}
            gutterBottom
          >
            {subtitle}
          </Typography>
        )}

        <TextField
          fullWidth
          multiline={textArea}
          rows={textArea ? 5 : 1}
          value={value}
          variant="outlined"
          autoComplete="off"
          inputProps={{ 'data-testid': `${id}-field`, disabled: true }}
          disabled
        />
      </Box>
    </Box>
  );
};
