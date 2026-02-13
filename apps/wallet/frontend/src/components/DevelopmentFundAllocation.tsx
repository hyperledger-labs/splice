// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';
import {
  Alert,
  Button,
  Card,
  CardContent,
  FormControl,
  FormHelperText,
  InputAdornment,
  OutlinedInput,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { DisableConditionally } from '@lfdecentralizedtrust/splice-common-frontend';
import BftAnsField from './BftAnsField';
import { DateTimePicker } from '@mui/x-date-pickers/DateTimePicker';
import dayjs from 'dayjs';
import { useWalletConfig } from '../utils/config';
import { useDevelopmentFundAllocationForm } from '../hooks/useDevelopmentFundAllocationForm';

const DevelopmentFundAllocation: React.FC = () => {
  const config = useWalletConfig();

  const {
    formKey,
    error,
    beneficiary,
    setBeneficiary,
    amount,
    setAmount,
    expiresAt,
    setExpiresAt,
    reason,
    setReason,
    amountNum,
    isAmountValid,
    amountExceedsAvailable,
    isValid,
    allocateMutation,
    isFundManager,
    unclaimedTotal,
  } = useDevelopmentFundAllocationForm();

  const disabled = !isFundManager;

  return (
    <Stack spacing={2}>
      <Typography variant="h4">Development Fund Allocation</Typography>
      <Card
        variant="outlined"
        sx={disabled ? { opacity: 0.5, pointerEvents: 'none' } : undefined}
      >
        <CardContent sx={{ paddingX: '64px' }}>
          <Stack key={formKey} spacing={3}>
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
              disabled={disabled}
            />

            <Stack direction="row" spacing={3}>
              <Stack spacing={1} sx={{ flex: 1 }}>
                <Typography variant="h6">Amount</Typography>
                <FormControl
                  fullWidth
                  disabled={disabled}
                  error={amount !== '' && (!isAmountValid || amountExceedsAvailable)}
                >
                  <OutlinedInput
                    id="development-fund-allocation-amount"
                    type="number"
                    value={amount}
                    onChange={event => setAmount(event.target.value)}
                    endAdornment={
                      <InputAdornment position="end">
                        {config.spliceInstanceNames.amuletNameAcronym}
                      </InputAdornment>
                    }
                    error={amount !== '' && (!isAmountValid || amountExceedsAvailable)}
                    inputProps={{
                      'aria-label': 'amount',
                    }}
                    disabled={disabled}
                  />
                  {amountExceedsAvailable && (
                    <FormHelperText>
                      Available: {unclaimedTotal.toFixed(4)}{' '}
                      {config.spliceInstanceNames.amuletNameAcronym}
                    </FormHelperText>
                  )}
                </FormControl>
              </Stack>

              <Stack spacing={1} sx={{ flex: 1 }}>
                <Typography variant="h6">Expires At</Typography>
                <DateTimePicker
                  label="Expires At"
                  value={expiresAt}
                  onChange={newValue => setExpiresAt(newValue)}
                  minDateTime={dayjs()}
                  disabled={disabled}
                  slotProps={{
                    textField: {
                      id: 'development-fund-allocation-expires-at',
                      fullWidth: true,
                    },
                  }}
                />
              </Stack>
            </Stack>

            <Typography variant="h6">Reason</Typography>
            <TextField
              id="development-fund-allocation-reason"
              label="Reason"
              multiline
              rows={3}
              value={reason}
              onChange={event => setReason(event.target.value)}
              placeholder="Enter the reason for this allocation"
              disabled={disabled}
            />

            <DisableConditionally
              conditions={[
                {
                  disabled: disabled,
                  reason: 'Only the Development Fund Manager can allocate funds.',
                },
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
                onClick={() =>
                  amountNum &&
                  expiresAt &&
                  allocateMutation.mutate({
                    beneficiary,
                    amount: amountNum,
                    expiresAt: expiresAt.toDate(),
                    reason,
                  })
                }
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
