// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Alert } from '@mui/material';

interface ProposalSubmissionErrorProps {
  error: Error | null;
}

export const ProposalSubmissionError: React.FC<ProposalSubmissionErrorProps> = props => {
  const { error } = props;
  return error ? (
    <Alert
      severity="error"
      sx={{ color: 'white' }}
      data-testid="proposal-submission-error"
    >{`Submission failed: ${error.message}`}</Alert>
  ) : null;
};
