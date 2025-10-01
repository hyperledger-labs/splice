// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Box, Typography } from '@mui/material';
import { ConfigChange } from '../../utils/types';
import { PartyId } from '@lfdecentralizedtrust/splice-common-frontend';

interface ConfigValuesChangesProps {
  changes: ConfigChange[];
  isSummaryView?: boolean;
}

export const ConfigValuesChanges: React.FC<ConfigValuesChangesProps> = props => {
  const { changes, isSummaryView } = props;
  const textColor = isSummaryView ? 'text.secondary' : 'text.primary';

  return (
    <Box
      sx={{ py: 1 }}
      id="proposal-details-config-changes-section"
      data-testid="proposal-details-config-changes-section"
    >
      <Typography
        variant={isSummaryView ? 'h5' : 'subtitle2'}
        color={isSummaryView ? 'text.primary' : 'text.secondary'}
        gutterBottom
        sx={{ mb: isSummaryView ? 2 : 4 }}
        data-testid="proposal-details-config-changes-section-title"
      >
        Proposed Changes
      </Typography>

      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, mb: 3 }}>
        {changes.length === 0 && (
          <Box sx={{ py: 1 }}>
            <Typography variant="body2" color={textColor}>
              No changes found.
            </Typography>
          </Box>
        )}

        {changes.map((change, index) => (
          <Box
            key={index}
            sx={{ display: 'flex', alignItems: 'center', gap: 2 }}
            data-testid="config-change"
          >
            <Typography
              variant="body1"
              sx={{ minWidth: 200 }}
              data-testid="config-change-field-label"
              color={textColor}
            >
              {change.label}
            </Typography>

            <Box
              sx={{
                px: 1.5,
                py: 0.5,
                bgcolor: 'rgba(255, 255, 255, 0.1)',
                borderRadius: 1,
                minWidth: 80,
                textAlign: 'center',
              }}
              data-testid="config-change-current-value-container"
            >
              {change.isId ? (
                <PartyId partyId={`${change.currentValue}`} id="config-change-current-value" />
              ) : (
                <Typography
                  variant="body2"
                  fontFamily="monospace"
                  data-testid="config-change-current-value"
                >
                  {change.currentValue}
                </Typography>
              )}
            </Box>

            <Typography variant="body1" sx={{ mx: 1 }}>
              →
            </Typography>

            <Box
              sx={{
                px: 1.5,
                py: 0.5,
                bgcolor: 'rgba(255, 255, 255, 0.1)',
                borderRadius: 1,
                minWidth: 80,
                textAlign: 'center',
              }}
              data-testid="config-change-new-value-container"
            >
              {change.isId ? (
                <PartyId partyId={`${change.newValue}`} id="config-change-new-value" />
              ) : (
                <Typography
                  variant="body2"
                  fontFamily="monospace"
                  data-testid="config-change-new-value"
                >
                  {change.newValue}
                </Typography>
              )}
            </Box>
          </Box>
        ))}
      </Box>
    </Box>
  );
};
