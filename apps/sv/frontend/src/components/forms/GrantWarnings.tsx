// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Alert } from '@mui/material';
import { useFeaturedAppGrantWarnings } from '../../hooks/useFeaturedAppGrantWarnings';

// Non-blocking grant warnings, isolated so its live subscriptions (provider + opsParties)
// re-render only here, not the whole form body, on each keystroke.
export const GrantWarnings: React.FC<{ provider: string; opsParties: string[] }> = ({
  provider,
  opsParties,
}) => {
  const warnings = useFeaturedAppGrantWarnings(provider, opsParties);
  return (
    <>
      {warnings.providerHasRightWarning && (
        <Alert
          severity="info"
          color="warning"
          variant="outlined"
          data-testid="grant-featured-app-provider-warning"
        >
          {warnings.providerHasRightWarning}
        </Alert>
      )}
      {warnings.opsOverlapWarning && (
        <Alert
          severity="info"
          color="warning"
          variant="outlined"
          data-testid="grant-featured-app-ops-overlap-warning"
        >
          {warnings.opsOverlapWarning}
        </Alert>
      )}
    </>
  );
};
