// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Box, TextField as MuiTextField, Typography } from '@mui/material';
import { useFieldContext } from '../../hooks/formContext';
import { PROPOSAL_SUMMARY_SUBTITLE, PROPOSAL_SUMMARY_TITLE } from '../../utils/constants';

export interface ProposalSummaryFieldProps {
  id: string;
  title?: string;
  optional?: boolean;
  subtitle?: string;
}

export const ProposalSummaryField: React.FC<ProposalSummaryFieldProps> = props => {
  const { title, optional, id, subtitle } = props;
  const field = useFieldContext<string>();

  return (
    <Box>
      <Typography variant="h6" gutterBottom>
        {title || PROPOSAL_SUMMARY_TITLE}
        {optional && (
          <Typography component="span" variant="body2" color="text.secondary" sx={{ ml: 1 }}>
            optional
          </Typography>
        )}
      </Typography>
      <MuiTextField
        fullWidth
        multiline
        rows={5}
        variant="outlined"
        autoComplete="off"
        value={field.state.value}
        onBlur={field.handleBlur}
        onChange={e => field.handleChange(e.target.value)}
        error={!field.state.meta.isValid}
        helperText={field.state.meta.errors?.[0]}
        inputProps={{ 'data-testid': id }}
      />
      <Typography variant="body2" sx={{ mt: 1 }} data-testid={`${id}-subtitle`}>
        {subtitle || PROPOSAL_SUMMARY_SUBTITLE}
      </Typography>
    </Box>
  );
};
