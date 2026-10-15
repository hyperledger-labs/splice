// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Box, Typography } from '@mui/material';
import { PartyId } from '@canton-network/splice-common-frontend';
import { CREATE_PROPOSAL_FIELD_BODY_SX } from '../../constants/createProposalLayout';
import { ConfigChange } from '../../utils/types';
import { isSwitchOverField } from '../forms/formValidators';
import { SwitchOverTimesValue } from './SwitchOverTimesValue';

interface ConfigValuesChangesProps {
  changes: ConfigChange[];
  isSummaryView?: boolean;
}

export const ConfigValuesChanges: React.FC<ConfigValuesChangesProps> = props => {
  const { changes, isSummaryView } = props;
  const textColor = isSummaryView ? undefined : 'text.primary';
  const summaryLabelSx = isSummaryView ? CREATE_PROPOSAL_FIELD_BODY_SX : undefined;

  return (
    <Box
      id="proposal-details-config-changes-section"
      data-testid="proposal-details-config-changes-section"
    >
      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
        {changes.length === 0 && (
          <Box sx={{ py: 1 }}>
            <Typography
              variant="body2"
              color={textColor}
              sx={isSummaryView ? CREATE_PROPOSAL_FIELD_BODY_SX : undefined}
            >
              No changes found.
            </Typography>
          </Box>
        )}

        {changes.map((change, index) => (
          <Box
            key={index}
            sx={{
              display: 'flex',
              alignItems: 'center',
              gap: 2,
              ...(change.disabled && {
                px: 1.5,
                py: 1,
                borderRadius: 1,
                borderLeft: '3px solid',
                borderColor: 'warning.main',
                bgcolor: 'rgba(255, 167, 38, 0.08)',
              }),
            }}
            data-testid="config-change"
            data-disabled={change.disabled ? 'true' : undefined}
          >
            <Box sx={{ minWidth: 200 }}>
              <Typography
                variant={isSummaryView ? undefined : 'body1'}
                data-testid="config-change-field-label"
                color={textColor}
                sx={summaryLabelSx}
              >
                {change.label}
              </Typography>
              {change.disabled && (
                <Typography
                  variant="caption"
                  color="warning.main"
                  data-testid="config-change-disabled-label"
                >
                  Disabled field
                </Typography>
              )}
            </Box>

            {change.currentValue && (
              <>
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
                  ) : isSwitchOverField(change.fieldName) ? (
                    <SwitchOverTimesValue
                      value={change.currentValue}
                      data-testid="config-change-current-value"
                    />
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
              </>
            )}

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
              ) : isSwitchOverField(change.fieldName) ? (
                <SwitchOverTimesValue
                  value={change.newValue}
                  data-testid="config-change-new-value"
                />
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
