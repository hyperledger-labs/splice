// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { DisableConditionally } from '@lfdecentralizedtrust/splice-common-frontend';
import { useMutation } from '@tanstack/react-query';
import BigNumber from 'bignumber.js';
import addHours from 'date-fns/addHours';
import React, { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { v4 as uuidv4 } from 'uuid';

import {
  Box,
  Button,
  Card,
  CardContent,
  Checkbox,
  FormControl,
  InputAdornment,
  NativeSelect,
  OutlinedInput,
  Stack,
  TextField,
  Typography,
} from '@mui/material';

import { useWalletClient } from '../contexts/WalletServiceContext';
import useAmuletPrice from '../hooks/scan-proxy/useAmuletPrice';
import useLookupTransferPreapproval from '../hooks/scan-proxy/useLookupTransferPreapproval';
import { useWalletConfig } from '../utils/config';
import BftAnsField from './BftAnsField';

const SendTransfer: React.FC = () => {
  const config = useWalletConfig();
  const { createTransferOffer, transferPreapprovalSend } = useWalletClient();
  const amuletPriceQuery = useAmuletPrice();

  const [receiver, setReceiver] = useState<string>('');
  const [usd, setUsdAmount] = useState<BigNumber | undefined>(undefined);
  const [ccAmountText, setCCAmountText] = useState<string>('1');
  const [expDays, setExpDays] = useState('1');
  const [description, setDescription] = useState<string>('');
  const [useTransferPreapproval, setUseTransferPreapproval] = useState<boolean>(true);
  const preapprovalResult = useLookupTransferPreapproval(receiver);

  const expiryOptions = [
    { name: '1 day', value: 1 },
    { name: '10 days', value: 10 },
    { name: '30 days', value: 30 },
    { name: '60 days', value: 60 },
    { name: '90 days', value: 90 },
  ];

  const ccAmount = useMemo(() => new BigNumber(ccAmountText), [ccAmountText]);

  // Only set deduplication id once. In the success case, it doesn't matter because of `isSending` & the `navigate`.
  // But: if the transfer is accepted by the BE, but the response fails to reach the FE (e.g., timeout),
  // you need to make sure that if the user clicks "Send" again it will be with the same key to prevent double-sends.
  const deduplicationId: string = useMemo(() => uuidv4(), []);

  const navigate = useNavigate();
  const createTransferOfferMutation = useMutation({
    mutationFn: async () => {
      const now = new Date();
      const expires = addHours(now, Number(expDays) * 24);
      return await createTransferOffer(receiver, ccAmount, description, expires, deduplicationId);
    },
    onSuccess: () => {
      navigate('/transactions');
    },
    onError: error => {
      // TODO (#5491): show an error to the user.
      console.error(
        `Failed to create transfer offer to ${receiver} of ${ccAmount} CC with trackingId ${deduplicationId}`,
        error
      );
    },
    // in case the participant is unavailable
    retry: 4,
  });

  const transferPreapprovalSendMutation = useMutation({
    mutationFn: async () => {
      return await transferPreapprovalSend(receiver, ccAmount, deduplicationId);
    },
    onSuccess: () => {
      navigate('/transactions');
    },
    onError: error => {
      // TODO (#5491): show an error to the user.
      console.error(
        `Failed to send transfer to ${receiver} of ${ccAmount} CC with deduplicationId ${deduplicationId}`,
        error
      );
    },
  });

  useMemo(() => {
    if (amuletPriceQuery.data) {
      const usdAmount = amuletPriceQuery.data.times(ccAmount);
      setUsdAmount(prev => (prev && prev.eq(usdAmount) ? prev : usdAmount));
    }
  }, [ccAmount, amuletPriceQuery.data]);

  const onCCAmountChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setCCAmountText(e.target.value);
  };

  return (
    <Stack mt={4} spacing={4} direction="column" justifyContent="center">
      <Typography mt={6} variant="h4">
        Transfers
      </Typography>
      <Card variant="outlined">
        <CardContent sx={{ paddingX: '64px' }}>
          <Stack direction="row" spacing={5} sx={{ justifyContent: 'space-between' }}>
            <Stack direction="column" mb={4} spacing={1}>
              <Typography variant="h6">Recipient</Typography>
              <BftAnsField
                name="Receiver"
                label="Receiver"
                aria-label="Receiver"
                id="create-offer-receiver"
                onPartyChanged={setReceiver}
              />
            </Stack>
            <Stack
              direction="column"
              sx={{ alignItems: 'flex-end', display: preapprovalResult.data ? undefined : 'none' }}
            >
              <Typography variant="h6">
                Receiver has approved incoming transfers, transfer directly instead of creating a
                transfer offer
              </Typography>
              <Checkbox
                id="use-transfer-preapproval-checkbox"
                checked={useTransferPreapproval}
                onChange={e => setUseTransferPreapproval(e.target.checked)}
              ></Checkbox>
            </Stack>
          </Stack>

          <Stack direction="column" mb={4} spacing={1}>
            <Typography variant="h6">Amount</Typography>
            <Box display="flex">
              <FormControl sx={{ marginRight: '32px', flexGrow: '1' }}>
                <OutlinedInput
                  id="create-offer-amulet-amount"
                  type="text"
                  value={ccAmountText}
                  onChange={onCCAmountChange}
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
          {!(preapprovalResult.data && useTransferPreapproval) && (
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
          )}
          {!(preapprovalResult.data && useTransferPreapproval) && (
            <Stack direction="column" mb={4} spacing={1}>
              <Typography variant="h6">
                Description <Typography variant="caption">(optional)</Typography>{' '}
              </Typography>
              <TextField
                id="create-offer-description"
                rows={4}
                multiline
                inputProps={{ 'aria-label': 'description' }}
                onChange={e => setDescription(e.target.value)}
              />
            </Stack>
          )}

          <DisableConditionally
            conditions={[
              {
                disabled: createTransferOfferMutation.isLoading,
                reason: 'Creating transfer offer...',
              },
              {
                disabled: transferPreapprovalSendMutation.isLoading,
                reason: 'Executing preapproved transfer...',
              },
              {
                disabled: preapprovalResult.isLoading,
                reason: 'Loading preapproval data...',
              },
            ]}
          >
            <Button
              id="create-offer-submit-button"
              variant="pill"
              fullWidth
              size="large"
              onClick={() =>
                useTransferPreapproval && preapprovalResult.data
                  ? transferPreapprovalSendMutation.mutate()
                  : createTransferOfferMutation.mutate()
              }
            >
              Send
            </Button>
          </DisableConditionally>
        </CardContent>
      </Card>
    </Stack>
  );
};

export default SendTransfer;
