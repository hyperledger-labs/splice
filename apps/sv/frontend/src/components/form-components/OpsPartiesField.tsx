// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Box, Button, IconButton, Stack, TextField, Typography } from '@mui/material';
import { DeleteOutline } from '@mui/icons-material';
import {
  CREATE_PROPOSAL_FIELD_HELPER_SX,
  CREATE_PROPOSAL_FIELD_LABEL_SX,
} from '../../constants/createProposalLayout';
import { useRef } from 'react';

// Shared opsParties editor (#7665 grant + #7674 amend). Controlled: the row list is the
// `value` (a string[] form field); add/edit/remove produce a new array via `onChange`.
// Decoupled from any specific form shape so both forms can reuse it. Validation is the
// caller's (validateOpsParties) and surfaced via `error`.
export interface OpsPartiesFieldProps {
  id: string;
  title: string;
  value: string[];
  onChange: (next: string[]) => void;
  onBlur?: () => void;
  error?: string | false;
  subtitle?: string;
}

const PLACEHOLDER = 'party::1220abcd…';

export const OpsPartiesField: React.FC<OpsPartiesFieldProps> = props => {
  const { id, title, value, onChange, onBlur, error, subtitle } = props;

  // Stable per-row keys. Index keys remap on remove (React reuses the wrong input DOM node
  // and drops focus). ids track rows; reconciled when value changes from outside (prepopulate/reset).
  const idSeq = useRef(0);
  const idsRef = useRef<number[]>([]);
  if (idsRef.current.length !== value.length) {
    const ids = idsRef.current.slice(0, value.length);
    while (ids.length < value.length) ids.push(idSeq.current++);
    idsRef.current = ids;
  }
  const ids = idsRef.current;

  const setRow = (index: number, next: string) =>
    onChange(value.map((row, i) => (i === index ? next : row)));
  const removeRow = (index: number) => {
    idsRef.current = idsRef.current.filter((_, i) => i !== index);
    onChange(value.filter((_, i) => i !== index));
  };
  const addRow = () => {
    idsRef.current = [...idsRef.current, idSeq.current++];
    onChange([...value, '']);
  };
  return (
    <Box data-testid={`${id}-component`}>
      <Typography component="p" sx={{ ...CREATE_PROPOSAL_FIELD_LABEL_SX, mb: 1 }}>
        {title}
      </Typography>
      {subtitle && (
        <Typography component="p" sx={{ ...CREATE_PROPOSAL_FIELD_HELPER_SX, mb: 1 }}>
          {subtitle}
        </Typography>
      )}

      <Stack spacing={1}>
        {value.map((row, i) => (
          <Box key={ids[i]} sx={{ display: 'flex', gap: 1, alignItems: 'flex-start' }}>
            <TextField
              fullWidth
              size="small"
              placeholder={PLACEHOLDER}
              value={row}
              onChange={e => setRow(i, e.target.value)}
              onBlur={onBlur}
              inputProps={{ 'data-testid': `${id}-row-${i}`, 'aria-label': `${title} ${i + 1}` }}
            />
            <IconButton
              aria-label="remove ops party"
              data-testid={`${id}-remove-${i}`}
              onClick={() => removeRow(i)}
            >
              <DeleteOutline />
            </IconButton>
          </Box>
        ))}

        <Button variant="outlined" data-testid={`${id}-add`} onClick={addRow}>
          Add party
        </Button>
      </Stack>

      {error && (
        <Typography
          component="p"
          data-testid={`${id}-error`}
          sx={{ ...CREATE_PROPOSAL_FIELD_HELPER_SX, color: 'error.main', mt: 1 }}
        >
          {error}
        </Typography>
      )}
    </Box>
  );
};
