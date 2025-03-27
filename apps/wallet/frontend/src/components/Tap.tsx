// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import { DisableConditionally, ErrorDisplay } from '@lfdecentralizedtrust/splice-common-frontend';
import BigNumber from 'bignumber.js';
import { useState } from 'react';

import { Button, Stack, TextField } from '@mui/material';

import { useTap } from '../hooks';

const Tap: React.FC = () => {
  const [tapValueText, setTapValueText] = useState<string>('0');

  const mutation = useTap();

  const isInvalidAmount = BigNumber(tapValueText).lte(0.0);
  return (
    <Stack direction="row" spacing={2} alignItems="center">
      <TextField
        error={isInvalidAmount}
        label="Amount"
        onChange={event => setTapValueText(event.target.value)}
        value={tapValueText}
        type="text"
        id="tap-amount-field"
      />
      <DisableConditionally
        conditions={[
          { disabled: mutation.isLoading, reason: 'Loading...' },
          { disabled: isInvalidAmount, reason: 'Invalid amount' },
        ]}
      >
        <Button
          variant="contained"
          onClick={() => mutation.mutate(BigNumber(tapValueText))}
          id="tap-button"
        >
          Tap
        </Button>
      </DisableConditionally>

      {mutation.isError ? (
        <ErrorDisplay message={'Tap operation failed'} details={JSON.stringify(mutation.error)} />
      ) : null}
    </Stack>
  );
};

export default Tap;
