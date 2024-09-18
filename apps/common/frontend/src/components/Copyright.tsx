// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';

import Link from '@mui/material/Link';
import Typography from '@mui/material/Typography';

const Copyright: React.FC = () => (
  <Typography
    variant="body1"
    color="text.secondary"
    align="center"
    position={'relative'}
    bottom={0}
    margin={2}
    padding={2}
  >
    {'Copyright © '}
    <Link color="inherit" href="https://digitalasset.com/">
      Digital Asset
    </Link>{' '}
    {new Date().getFullYear()}.
  </Typography>
);

export default Copyright;
