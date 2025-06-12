// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React, { useMemo, useState } from 'react';
import useAmuletPrice from '../hooks/scan-proxy/useAmuletPrice';
import BigNumber from 'bignumber.js';
import { Box, FormControl, InputAdornment, OutlinedInput, Stack, Typography } from '@mui/material';
import { useWalletConfig } from '../utils/config';

interface AmountInputProps {
  ccAmountText: string;
  setCcAmountText: (amount: string) => void;
}
export const AmountInput: React.FC<AmountInputProps> = ({ setCcAmountText, ccAmountText }) => {
  const config = useWalletConfig();
  const amuletPriceQuery = useAmuletPrice();
  const [usd, setUsdAmount] = useState<BigNumber | undefined>(undefined);

  useMemo(() => {
    if (amuletPriceQuery.data) {
      const usdAmount = amuletPriceQuery.data.times(ccAmountText);
      setUsdAmount(prev => (prev && prev.eq(usdAmount) ? prev : usdAmount));
    }
  }, [ccAmountText, amuletPriceQuery.data]);

  return (
    <Stack direction="column" mb={4} spacing={1}>
      <Typography variant="h6">Amount</Typography>
      <Box display="flex">
        <FormControl sx={{ marginRight: '32px', flexGrow: '1' }}>
          <OutlinedInput
            id="amulet-amount"
            type="text"
            value={ccAmountText}
            onChange={event => setCcAmountText(event.target.value)}
            endAdornment={
              <InputAdornment position="end">
                {config.spliceInstanceNames.amuletNameAcronym}
              </InputAdornment>
            }
            aria-describedby="outlined-amount-amulet-helper-text"
            error={BigNumber(ccAmountText).lte(0.0)}
            inputProps={{
              'aria-label': 'amount',
            }}
          />
        </FormControl>
        {/* Slight deviation from the original design here. The USD field is below the CC field in the figma designs */}
        <FormControl>
          <OutlinedInput
            disabled
            id="usd-amount"
            value={usd ?? '...'}
            endAdornment={<InputAdornment position="end">USD</InputAdornment>}
            aria-describedby="outlined-amount-usd-helper-text"
            inputProps={{
              'aria-label': 'amount',
            }}
          />
        </FormControl>
      </Box>
    </Stack>
  );
};
export default AmountInput;
