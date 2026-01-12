// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { DateWithDurationDisplay } from '@lfdecentralizedtrust/splice-common-frontend';
import { Decimal } from 'decimal.js-light';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { FormControl, Stack, TextField, Typography } from '@mui/material';
import { DesktopDateTimePicker } from '@mui/x-date-pickers/DesktopDateTimePicker';
import { LocalizationProvider } from '@mui/x-date-pickers';
import { AdapterDayjs } from '@mui/x-date-pickers/AdapterDayjs';
import dayjs, { Dayjs } from 'dayjs';
import utc from 'dayjs/plugin/utc';

import { getUTCWithOffset } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { ActionRequiringConfirmation } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';
import { ActionFromForm, actionFromFormIsError } from '../VoteRequest';

dayjs.extend(utc);

function asCreateUnallocatedUnclaimedActivityRecord(action?: ActionFromForm):
  | {
      beneficiary: string;
      amount: string;
      reason: string;
      expiresAt: string;
    }
  | undefined {
  return action &&
    !actionFromFormIsError(action) &&
    action.tag === 'ARC_DsoRules' &&
    action.value.dsoAction.tag === 'SRARC_CreateUnallocatedUnclaimedActivityRecord'
    ? action.value.dsoAction.value
    : undefined;
}

const CreateUnallocatedUnclaimedActivityRecord: React.FC<{
  chooseAction: (action: ActionRequiringConfirmation) => void;
  action?: ActionFromForm;
  effectivity: Dayjs;
  setIsValidAmount: (isValid: boolean) => void;
  summary: string;
}> = ({ chooseAction, action, effectivity, setIsValidAmount, summary }) => {
  const existing = asCreateUnallocatedUnclaimedActivityRecord(action);

  const [beneficiary, setBeneficiary] = useState(existing?.beneficiary ?? '');
  const [amount, setAmount] = useState(existing?.amount ?? '');
  const [mustMintBefore, setMustMintBefore] = useState<Dayjs>(dayjs());
  const [amountError, setAmountError] = useState<string | null>(null);

  const summaryRef = useRef(summary);
  summaryRef.current = summary;

  useEffect(() => {
    if (existing?.expiresAt) {
      setMustMintBefore(dayjs(existing.expiresAt));
    } else {
      setMustMintBefore(effectivity.add(2, 'day'));
    }
  }, [existing?.expiresAt, effectivity]);

  const updateAction = useCallback(
    (beneficiary: string, amount: string, mustMintBefore: Dayjs) => {
      chooseAction({
        tag: 'ARC_DsoRules',
        value: {
          dsoAction: {
            tag: 'SRARC_CreateUnallocatedUnclaimedActivityRecord',
            value: {
              beneficiary,
              amount,
              reason: summaryRef.current,
              expiresAt: mustMintBefore.toISOString(),
            },
          },
        },
      });
    },
    [chooseAction]
  );

  useEffect(() => {
    if (beneficiary && amount && summary && mustMintBefore && !amountError) {
      updateAction(beneficiary, amount, mustMintBefore);
    }
  }, [beneficiary, amount, summary, mustMintBefore, amountError, updateAction]);

  return (
    <Stack direction="column" mb={4} spacing={1}>
      <Typography variant="h6">Beneficiary</Typography>
      <FormControl fullWidth>
        <TextField
          id="create-beneficiary"
          inputProps={{ 'data-testid': 'create-beneficiary' }}
          value={beneficiary}
          onChange={e => setBeneficiary(e.target.value)}
        />
      </FormControl>

      <Typography variant="h6">Amount</Typography>
      <FormControl fullWidth>
        <TextField
          id="create-amount"
          type="number"
          value={amount}
          inputProps={{ 'data-testid': 'create-amount' }}
          onChange={e => {
            const newValue = e.target.value;
            setAmount(newValue);

            // TODO(#1520): Check that amount <=  amount of unclaimed rewards available.
            // https://github.com/hyperledger-labs/splice/issues/1520
            try {
              const decimal = new Decimal(newValue);
              if (decimal.lte(0)) {
                setAmountError('Amount must be a positive number');
                setIsValidAmount(false);
              } else {
                setAmountError(null);
                setIsValidAmount(true);
              }
            } catch {
              setAmountError('Amount must be a positive number');
              setIsValidAmount(false);
            }
          }}
          error={!!amountError}
          helperText={amountError}
        />
      </FormControl>

      <Typography variant="h6">Must Mint Before</Typography>
      <LocalizationProvider dateAdapter={AdapterDayjs}>
        <DesktopDateTimePicker
          label={`Enter time in local timezone (${getUTCWithOffset()})`}
          value={mustMintBefore}
          ampm={false}
          format="YYYY-MM-DD HH:mm"
          minDateTime={dayjs()}
          readOnly={false}
          onChange={d => setMustMintBefore(d ?? dayjs())}
          slotProps={{
            textField: {
              id: 'datetime-picker-unallocated-expires-at',
              inputProps: {
                'data-testid': 'datetime-picker-unallocated-expires-at',
              },
            },
          }}
          closeOnSelect
        />
      </LocalizationProvider>
      <Typography variant="body2" mt={1}>
        Expires{' '}
        <DateWithDurationDisplay datetime={mustMintBefore.toDate()} enableDuration onlyDuration />
      </Typography>
    </Stack>
  );
};

export default CreateUnallocatedUnclaimedActivityRecord;
