// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Box, Stack, Typography } from '@mui/material';
import { CopyableIdentifier } from '../ui';

const VISUALLY_HIDDEN_SX = {
  position: 'absolute',
  width: 1,
  height: 1,
  overflow: 'hidden',
  clip: 'rect(0 0 0 0)',
  whiteSpace: 'nowrap',
} as const;

// Read-only renderer for an opsParties value (review summary + vote-request details).
// None/empty renders the default hint; otherwise a stacked list of copyable party ids.
export const OpsPartiesDisplay: React.FC<{
  parties: string[] | null;
  idPrefix: string;
}> = ({ parties, idPrefix }) =>
  parties === null || parties.length === 0 ? (
    <Typography variant="body2" color="text.secondary" data-testid={`${idPrefix}-none`}>
      None — uses default (no ops parties)
    </Typography>
  ) : (
    <Stack spacing={1} data-testid={idPrefix}>
      {parties.map((p, i) => (
        <CopyableIdentifier key={i} value={p} size="small" data-testid={`${idPrefix}-${i}`} />
      ))}
    </Stack>
  );

// opsParties diff for the amend details/review: one line per added (+) / removed (−) party.
// On-ledger the change is a whole-list replace; added/removed is only how we present base→next.
export const OpsPartiesDiff: React.FC<{
  added: string[];
  removed: string[];
  idPrefix: string;
}> = ({ added, removed, idPrefix }) =>
  added.length === 0 && removed.length === 0 ? (
    <Typography variant="body2" color="text.secondary" data-testid={`${idPrefix}-nochange`}>
      No change
    </Typography>
  ) : (
    <Stack spacing={1} data-testid={idPrefix}>
      {added.map((p, i) => (
        <Box key={`a${i}`} sx={{ display: 'flex', gap: 1, alignItems: 'center' }}>
          <Typography variant="body2" color="success.main" sx={{ minWidth: 14 }}>
            +
          </Typography>
          <Box component="span" sx={VISUALLY_HIDDEN_SX}>
            Added:
          </Box>
          <CopyableIdentifier value={p} size="small" data-testid={`${idPrefix}-added-${i}`} />
        </Box>
      ))}
      {removed.map((p, i) => (
        <Box key={`r${i}`} sx={{ display: 'flex', gap: 1, alignItems: 'center' }}>
          <Typography variant="body2" color="error.main" sx={{ minWidth: 14 }}>
            −
          </Typography>
          <Box component="span" sx={VISUALLY_HIDDEN_SX}>
            Removed:
          </Box>
          <CopyableIdentifier value={p} size="small" data-testid={`${idPrefix}-removed-${i}`} />
        </Box>
      ))}
    </Stack>
  );
