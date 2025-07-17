// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';
import { Stack, Typography } from '@mui/material';

const MetaDisplay: React.FC<{ meta: { [key: string]: string } }> = ({ meta }) => {
  return (
    <Stack spacing={2}>
      {Object.keys(meta)
        .toSorted()
        .map(key => {
          const value = meta[key];
          return (
            <Stack key={`meta-${key}`} overflow="hidden" textOverflow="ellipsis" maxWidth="150px">
              <Typography variant="body2" noWrap>
                {key}:
              </Typography>
              <Typography variant="body2" noWrap>
                {value}
              </Typography>
            </Stack>
          );
        })}
    </Stack>
  );
};
export default MetaDisplay;
