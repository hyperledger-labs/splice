// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  DisableConditionally,
  ErrorDisplay,
  Loading,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { useMutation } from '@tanstack/react-query';
import BigNumber from 'bignumber.js';
import addHours from 'date-fns/addHours';
import React, { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import { v4 as uuidv4 } from 'uuid';

import {
  Alert,
  Button,
  Card,
  CardContent,
  Checkbox,
  FormControl,
  FormControlLabel,
  NativeSelect,
  Stack,
  Switch,
  TextField,
  Typography,
} from '@mui/material';

import { useWalletClient } from '../contexts/WalletServiceContext';
import useLookupTransferPreapproval from '../hooks/scan-proxy/useLookupTransferPreapproval';
import BftAnsField from './BftAnsField';
import { useFeatureSupport } from '../hooks/useFeatureSupport';
import AmountInput from './AmountInput';

const SendTransfer: React.FC = () => {
  const { createTransferOffer, transferPreapprovalSend, createTransferViaTokenStandard } =
    useWalletClient();

  const [useTokenStandardTransfer, setUseTokenStandardTransfer] = useState(true);
  const [receiver, setReceiver] = useState<string>('');
  const [ccAmountText, setCCAmountText] = useState<string>('1');
  const [expDays, setExpDays] = useState('1');
  const [description, setDescription] = useState<string>('');
  const [useTransferPreapproval, setUseTransferPreapproval] = useState<boolean>(true);
  const preapprovalResult = useLookupTransferPreapproval(receiver);
  const featureSupport = useFeatureSupport();

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
      if (featureSupport.data?.tokenStandard && useTokenStandardTransfer) {
        return await createTransferViaTokenStandard(
          receiver,
          ccAmount,
          description,
          expires,
          deduplicationId
        );
      } else {
        return await createTransferOffer(receiver, ccAmount, description, expires, deduplicationId);
      }
    },
    onSuccess: () => {
      navigate('/transactions');
    },
    onError: error => {
      // TODO (DACH-NY/canton-network-node#5491): show an error to the user.
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
      if (featureSupport.data?.tokenStandard && useTokenStandardTransfer) {
        const now = new Date();
        const expires = addHours(now, Number(expDays) * 24);
        return await createTransferViaTokenStandard(
          receiver,
          ccAmount,
          description,
          expires,
          deduplicationId
        );
      } else {
        return await transferPreapprovalSend(receiver, ccAmount, deduplicationId, description);
      }
    },
    onSuccess: () => {
      navigate('/transactions');
    },
    onError: error => {
      // TODO (DACH-NY/canton-network-node#5491): show an error to the user.
      console.error(
        `Failed to send transfer to ${receiver} of ${ccAmount} CC with deduplicationId ${deduplicationId}`,
        error
      );
    },
  });

  if (featureSupport.isLoading) {
    return <Loading />;
  } else if (featureSupport.isError) {
    return <ErrorDisplay message="Failed to load Feature Support" />;
  }

  return (
    <Stack mt={4} spacing={4} direction="column" justifyContent="center">
      <Typography mt={6} variant="h4">
        Transfers
      </Typography>
      <Card variant="outlined">
        <CardContent sx={{ paddingX: '64px' }}>
          <Stack direction="column" spacing={1} sx={{ justifyContent: 'space-between' }}>
            <Stack direction="column" mb={4} spacing={1}>
              {featureSupport.data?.tokenStandard ? (
                <FormControlLabel
                  control={
                    <Switch
                      id="toggle-token-standard-transfer"
                      checked={useTokenStandardTransfer}
                      onChange={(_evt, checked: boolean) => setUseTokenStandardTransfer(checked)}
                    />
                  }
                  label="Use Token Standard Transfer"
                />
              ) : null}
              {!useTokenStandardTransfer ? (
                <Alert severity="warning">
                  Legacy transfer offers can only be accepted through the splice wallet which only
                  supports parties not relying on external signing. Token standard transfers on the
                  other hand can be accepted by any token standard compliant wallet including those
                  relying on external signing.
                </Alert>
              ) : null}
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
              direction="row"
              sx={{ alignItems: 'center', display: preapprovalResult.data ? undefined : 'none' }}
            >
              <Checkbox
                id="use-transfer-preapproval-checkbox"
                checked={useTransferPreapproval}
                onChange={e => setUseTransferPreapproval(e.target.checked)}
              ></Checkbox>
              <Typography variant="h6">
                Receiver has approved incoming transfers, transfer directly instead of creating a
                transfer offer
              </Typography>
            </Stack>
          </Stack>

          <AmountInput
            idPrefix="create-offer"
            ccAmountText={ccAmountText}
            setCcAmountText={setCCAmountText}
          />
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
          {featureSupport.data?.transferPreapprovalDescription ||
          !useTransferPreapproval ||
          !preapprovalResult.data ? (
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
          ) : null}

          <DisableConditionally
            conditions={[
              {
                disabled: createTransferOfferMutation.isPending,
                reason: 'Creating transfer offer...',
              },
              {
                disabled: transferPreapprovalSendMutation.isPending,
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
