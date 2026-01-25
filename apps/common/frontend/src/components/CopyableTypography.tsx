// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';

import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import { Typography, TypographyProps } from '@mui/material';
import IconButton from '@mui/material/IconButton';

export type CopyableTypographyProps = {
  text: string;
  maxWidth?: string;
} & TypographyProps;

const CopyableTypography: React.FC<CopyableTypographyProps> = props => {
  const { text, maxWidth, ...typographyProps } = props;
  const handleClick = () => navigator.clipboard.writeText(text);

  return (
    <div style={{ display: 'flex', alignItems: 'center' }}>
      <div
        style={{
          display: 'inline-flex',
          maxWidth: maxWidth || '200px',
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          fontWeight: 'lighter',
        }}
      >
        <Typography {...typographyProps}>{text}</Typography>
      </div>
      <IconButton onClick={handleClick}>
        <ContentCopyIcon fontSize={'small'} />
      </IconButton>
    </div>
  );
};

export default CopyableTypography;
