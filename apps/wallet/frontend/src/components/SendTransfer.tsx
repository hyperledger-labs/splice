import BigNumber from 'bignumber.js';
import { DirectoryField } from 'common-frontend';
import addHours from 'date-fns/addHours';
import React, { useCallback, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { v4 as uuidv4 } from 'uuid';

import {
  Box,
  Button,
  Card,
  CardContent,
  FormControl,
  InputAdornment,
  NativeSelect,
  OutlinedInput,
  Stack,
  TextField,
  Typography,
} from '@mui/material';

import { useWalletClient } from '../contexts/WalletServiceContext';
import { useCoinPrice } from '../hooks/useCoinPrice';

const SendTransfer: React.FC = () => {
  const { createTransferOffer } = useWalletClient();
  const coinPriceQuery = useCoinPrice();

  const [receiver, setReceiver] = useState<string>('');
  const [usd, setUsdAmount] = useState<BigNumber | undefined>(undefined);
  const [ccAmount, setCCAmount] = useState<BigNumber>(BigNumber(1));
  const [expDays, setExpDays] = useState('1');
  const [description, setDescription] = useState<string>('');

  const expiryOptions = [
    { name: '1 day', value: 1 },
    { name: '10 days', value: 10 },
    { name: '30 days', value: 30 },
    { name: '60 days', value: 60 },
    { name: '90 days', value: 90 },
  ];

  const [isSending, setIsSending] = useState<boolean>(false);
  // Only set idempotencyKey once. In the success case, it doesn't matter because of `isSending` & the `navigate`.
  // But: if the transfer is accepted by the BE, but the response fails to reach the FE (e.g., timeout),
  // you need to make sure that if the user clicks "Send" again it will be with the same key to prevent double-sends.
  const idempotencyKey: string = useMemo(() => uuidv4(), []);

  const navigate = useNavigate();

  const handleSendTransfer = async () => {
    setIsSending(true); // Disable the button to prevent double-clicks

    const now = new Date();
    const expires = addHours(now, Number(expDays) * 24);

    await createTransferOffer(receiver, ccAmount, description, expires, idempotencyKey).then(
      () => navigate('/transactions'),
      err => {
        // TODO (#2831): show an error to the user.
        console.error(
          `Failed to send transfer to ${receiver} of ${ccAmount} CC with idempotencyKey ${idempotencyKey}`,
          err
        );
        setIsSending(false); // allow the user to try again, with the same idempotencyKey
      }
    );
  };

  const convertUsd = useCallback(() => {
    if (coinPriceQuery.data) {
      setUsdAmount(coinPriceQuery.data.times(ccAmount));
    }
  }, [ccAmount, coinPriceQuery.data]);

  const onCCAmountChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setCCAmount(BigNumber(e.target.value));
    convertUsd();
  };

  return (
    <Stack mt={4} spacing={4} direction="column" justifyContent="center">
      <Typography mt={6} variant="h4">
        Transfers
      </Typography>
      <Card variant="outlined">
        <CardContent sx={{ paddingX: '64px' }}>
          <Stack direction="column" mb={4} spacing={1}>
            <Typography variant="h6">Recipient</Typography>
            <DirectoryField
              id="create-offer-receiver"
              label="Receiver"
              onPartyChanged={setReceiver}
            />
          </Stack>

          <Stack direction="column" mb={4} spacing={1}>
            <Typography variant="h6">Amount</Typography>
            <Box display="flex">
              <FormControl sx={{ marginRight: '32px', flexGrow: '1' }}>
                <OutlinedInput
                  id="create-offer-cc-amount"
                  type="number"
                  value={ccAmount}
                  onChange={onCCAmountChange}
                  endAdornment={<InputAdornment position="end">CC</InputAdornment>}
                  aria-describedby="outlined-amount-cc-helper-text"
                  error={BigNumber(ccAmount).lte(0.0)}
                  inputProps={{
                    'aria-label': 'amount',
                    min: 0,
                  }}
                />
              </FormControl>
              {/* Slight deviation from the original design here. The USD field is below the CC field in the figma designs */}
              <FormControl>
                <OutlinedInput
                  disabled
                  id="create-offer-usd-amount"
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

          <Stack direction="column" mb={4} spacing={1}>
            <Typography variant="h6">Expiration</Typography>
            <FormControl fullWidth>
              <NativeSelect
                inputProps={{ id: 'create-offer-expiration-days' }}
                value={expDays}
                onChange={e => setExpDays(e.target.value)}
              >
                {expiryOptions.map((exp, index) => (
                  <option key={'exp-option-' + index} value={exp.value}>
                    {exp.name}
                  </option>
                ))}
              </NativeSelect>
            </FormControl>
          </Stack>

          <Stack direction="column" mb={4} spacing={1}>
            <Typography variant="h6">
              Description <Typography variant="caption">(optional)</Typography>{' '}
            </Typography>
            <TextField
              id="create-offer-description"
              rows={4}
              multiline
              onChange={e => setDescription(e.target.value)}
            />
          </Stack>

          <Button
            id="create-offer-submit-button"
            variant="pill"
            fullWidth
            size="large"
            onClick={handleSendTransfer}
            disabled={isSending}
          >
            Send
          </Button>
        </CardContent>
      </Card>
    </Stack>
  );
};
export default SendTransfer;
