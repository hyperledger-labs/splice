// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';

import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import { InputBase, styled } from '@mui/material';
import IconButton from '@mui/material/IconButton';

export function updateIdFromEventId(eventId: string): string {
  return eventId.replace(/^#/, '').split(':').slice(-2)[0];
}

export const UpdateId: React.FC<{ updateId: string }> = ({ updateId }) => (
  <div
    className={`update-id`}
    data-selenium-text={updateId}
    style={{ display: 'flex', alignItems: 'center' }}
  >
    <UpdateStyled
      disabled
      readOnly
      value={updateId}
      endAdornment={
        <IconButton onClick={() => navigator.clipboard.writeText(updateId)}>
          <ContentCopyIcon fontSize={'small'} />
        </IconButton>
      }
    />
  </div>
);

const UpdateStyled = styled(InputBase)(({ theme }) => ({
  '& > .MuiInputBase-input.Mui-disabled': {
    color: theme.palette.colors.neutral[80],
    WebkitTextFillColor: theme.palette.colors.neutral[80],
    backgroundColor: theme.palette.colors.neutral[15],
    borderRadius: '5px',
    padding: theme.spacing(1),
    paddingRight: theme.spacing(0.5),
    textOverflow: 'ellipsis',
  },
  padding: 0,
  margin: 0,
  width: '100%',
}));
