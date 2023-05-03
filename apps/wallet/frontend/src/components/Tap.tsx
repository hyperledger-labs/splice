import * as React from 'react';
import { useMutation } from '@tanstack/react-query';
import BigNumber from 'bignumber.js';
import { useState } from 'react';

import { Button, Stack, TextField } from '@mui/material';

import { useWalletClient } from '../contexts/WalletServiceContext';

const Tap: React.FC = () => {
  const [tapValue, setTapValue] = useState<BigNumber>(new BigNumber(0));
  const { tap } = useWalletClient();

  const mutation = useMutation({
    mutationFn: () => {
      const decVal = tapValue;
      const strVal = decVal.isInteger() ? decVal.toFixed(1) : decVal.toString();
      return tap(strVal);
    },
  });

  const isInvalidAmount = tapValue.lte(0.0);
  return (
    <Stack direction="row">
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
        onClick={() => mutation.mutate()}
        id="tap-button"
      >
        Tap
      </Button>
    </Stack>
  );
};

export default Tap;
