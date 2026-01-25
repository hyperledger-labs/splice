// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';

import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import { InputBase, styled } from '@mui/material';
import IconButton from '@mui/material/IconButton';

export type PartyIdProps = {
  partyId: string;
  noCopy?: boolean;
  className?: string;
  id?: string;
};

const PartyId: React.FC<PartyIdProps> = ({ className, id, partyId, noCopy }) => (
  <div
    id={id}
    className={`party-id ${className}`}
    data-selenium-text={partyId}
    style={{ display: 'flex', alignItems: 'center' }}
  >
    <PartyStyled
      disabled
      readOnly
      value={partyId}
      inputProps={{ 'data-testid': `${id}-input` }}
      endAdornment={
        !noCopy && (
          <IconButton onClick={() => navigator.clipboard.writeText(partyId)}>
            <ContentCopyIcon fontSize={'small'} />
          </IconButton>
        )
      }
    />
  </div>
);

const PartyStyled = styled(InputBase)(({ theme }) => ({
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

export default PartyId;
