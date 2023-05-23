import * as React from 'react';
import BigNumber from 'bignumber.js';
import { ErrorDisplay } from 'common-frontend';
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
      <Button
        variant="contained"
        disabled={isInvalidAmount}
        onClick={() => mutation.mutate(BigNumber(tapValueText))}
        id="tap-button"
      >
        Tap
      </Button>

      {mutation.isError ? <ErrorDisplay message={'Tap operation failed'} /> : null}
    </Stack>
  );
};

export default Tap;
