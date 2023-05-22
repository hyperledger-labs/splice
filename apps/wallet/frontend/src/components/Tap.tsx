import * as React from 'react';
import BigNumber from 'bignumber.js';
import { ErrorDisplay } from 'common-frontend';
import { useState } from 'react';

import { Button, Stack, TextField } from '@mui/material';

import { useTap } from '../hooks';

const Tap: React.FC = () => {
  const [tapValue, setTapValue] = useState<BigNumber>(new BigNumber(0));

  const mutation = useTap();

  const isInvalidAmount = tapValue.lte(0.0);
  return (
    <Stack direction="row" spacing={2} alignItems="center">
      <TextField
        error={isInvalidAmount}
        label="Amount"
        onChange={event => setTapValue(new BigNumber(event.target.value))}
        value={tapValue}
        type="number"
        id="tap-amount-field"
      />
      <Button
        variant="contained"
        disabled={isInvalidAmount}
        onClick={() => mutation.mutate(tapValue)}
        id="tap-button"
      >
        Tap
      </Button>

      {mutation.isError ? <ErrorDisplay message={'Tap operation failed'} /> : null}
    </Stack>
  );
};

export default Tap;
