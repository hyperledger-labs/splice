// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React, { useState } from 'react';
import { useWalletClient } from '../contexts/WalletServiceContext';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Card, CardContent, Stack, TextField, Typography } from '@mui/material';
import { DisableConditionally } from '@lfdecentralizedtrust/splice-common-frontend';
import BftAnsField from './BftAnsField';
import AmountInput from './AmountInput';
import BigNumber from 'bignumber.js';
import { DateTimePicker } from '@mui/x-date-pickers/DateTimePicker';
import dayjs, { Dayjs } from 'dayjs';

const DevelopmentFundAllocation: React.FC = () => {
  const { allocateDevelopmentFundCoupon } = useWalletClient();
  const queryClient = useQueryClient();
  const [error, setError] = useState<object | null>(null);
  const [beneficiary, setBeneficiary] = useState<string>('');
  const [amount, setAmount] = useState<string>('');
  const [expiresAt, setExpiresAt] = useState<Dayjs | null>(null);
  const [reason, setReason] = useState<string>('');

  const isValid =
    beneficiary &&
    amount &&
    BigNumber(amount).gt(0) &&
    expiresAt &&
    expiresAt.isAfter(dayjs()) &&
    reason.trim().length > 0;

  const allocateMutation = useMutation({
    mutationFn: async () => {
      if (!isValid || !expiresAt) {
        throw new Error('Form is not valid');
      }
      return await allocateDevelopmentFundCoupon(
        beneficiary,
        new BigNumber(amount),
        expiresAt.toDate(),
        reason
      );
    },
    onSuccess: () => {
      setError(null);
      setBeneficiary('');
      setAmount('');
      setExpiresAt(null);
      setReason('');
      // Invalidate queries to refresh the coupon list
      queryClient.invalidateQueries({ queryKey: ['developmentFundCoupons'] });
      queryClient.invalidateQueries({ queryKey: ['developmentFundTotal'] });
    },
    onError: error => {
      console.error('Failed to allocate development fund coupon', error);
      setError(error);
    },
  });

  return (
    <Stack spacing={2}>
      <Typography variant="h4">Development Fund Allocation</Typography>
      <Card variant="outlined">
        <CardContent sx={{ paddingX: '64px' }}>
          <Stack spacing={3}>
            {error ? (
              <Alert severity="error">Failed to allocate: {JSON.stringify(error)}</Alert>
            ) : null}

            <Typography variant="h6">Beneficiary</Typography>
            <BftAnsField
              name="Beneficiary"
              label="Beneficiary"
              aria-label="Beneficiary"
              id="development-fund-allocation-beneficiary"
              onPartyChanged={setBeneficiary}
            />

            <AmountInput
              idPrefix="development-fund-allocation"
              ccAmountText={amount}
              setCcAmountText={setAmount}
            />

            <Typography variant="h6">Expires At</Typography>
            <DateTimePicker
              label="Expires At"
              value={expiresAt}
              onChange={newValue => setExpiresAt(newValue)}
              minDateTime={dayjs()}
              slotProps={{
                textField: {
                  id: 'development-fund-allocation-expires-at',
                  fullWidth: true,
                },
              }}
            />

            <Typography variant="h6">Reason</Typography>
            <TextField
              id="development-fund-allocation-reason"
              label="Reason"
              multiline
              rows={3}
              value={reason}
              onChange={event => setReason(event.target.value)}
              placeholder="Enter the reason for this allocation"
            />

            <DisableConditionally
              conditions={[
                {
                  disabled: allocateMutation.isPending,
                  reason: 'Allocating...',
                },
                {
                  disabled: !isValid,
                  reason: 'Form is not valid, please check all fields.',
                },
              ]}
            >
              <Button
                id="development-fund-allocation-submit-button"
                variant="pill"
                fullWidth
                size="large"
                onClick={() => allocateMutation.mutate()}
              >
                Allocate
              </Button>
            </DisableConditionally>
          </Stack>
        </CardContent>
      </Card>
    </Stack>
  );
};

export default DevelopmentFundAllocation;
