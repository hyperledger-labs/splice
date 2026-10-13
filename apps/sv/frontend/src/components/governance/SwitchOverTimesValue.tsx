// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Box, Typography } from '@mui/material';
import {
  SWITCH_OVER_UNSET_LABEL,
  switchOverConfigValueToDisplayEntries,
} from '../forms/formValidators';

interface SwitchOverTimesValueProps {
  value: string | null | undefined;
  'data-testid'?: string;
}

export const SwitchOverTimesValue: React.FC<SwitchOverTimesValueProps> = ({
  value,
  'data-testid': testId,
}) => {
  const entries = switchOverConfigValueToDisplayEntries(value);

  if (entries.length === 0) {
    return (
      <Typography variant="body2" fontFamily="monospace" data-testid={testId}>
        {SWITCH_OVER_UNSET_LABEL}
      </Typography>
    );
  }

  return (
    <Box
      data-testid={testId}
      sx={{ display: 'flex', flexDirection: 'column', gap: 0.5, textAlign: 'left' }}
    >
      {entries.map(entry => (
        <Typography key={entry.key} variant="body2" fontFamily="monospace">
          {entry.key} → {entry.time}
        </Typography>
      ))}
    </Box>
  );
};
