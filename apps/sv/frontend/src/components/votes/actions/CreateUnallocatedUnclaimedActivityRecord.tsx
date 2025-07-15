// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { DateWithDurationDisplay } from '@lfdecentralizedtrust/splice-common-frontend';
import { Decimal } from 'decimal.js-light';
import React, { useCallback, useEffect, useState } from 'react';
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
}> = ({ chooseAction, action, effectivity }) => {
  const existing = asCreateUnallocatedUnclaimedActivityRecord(action);

  const [beneficiary, setBeneficiary] = useState(existing?.beneficiary ?? '');
  const [amount, setAmount] = useState(existing?.amount ?? '');
  const [reason, setReason] = useState(existing?.reason ?? '');
  const [expiresAt, setExpiresAt] = useState<Dayjs>(dayjs());
  const [amountError, setAmountError] = useState<string | null>(null);

  useEffect(() => {
    if (existing?.expiresAt) {
      setExpiresAt(dayjs(existing.expiresAt));
    } else {
      setExpiresAt(effectivity.add(1, 'day'));
    }
  }, [existing?.expiresAt, effectivity]);

  const updateAction = useCallback(
    (beneficiary: string, amount: string, reason: string, expiresAt: Dayjs) => {
      chooseAction({
        tag: 'ARC_DsoRules',
        value: {
          dsoAction: {
            tag: 'SRARC_CreateUnallocatedUnclaimedActivityRecord',
            value: {
              beneficiary,
              amount,
              reason,
              expiresAt: expiresAt.toISOString(),
            },
          },
        },
      });
    },
    [chooseAction]
  );

  useEffect(() => {
    if (beneficiary && amount && reason && expiresAt && !amountError) {
      updateAction(beneficiary, amount, reason, expiresAt);
    }
  }, [beneficiary, amount, reason, expiresAt, amountError, updateAction]);

  return (
    <Stack direction="column" mb={4} spacing={1}>
      <Typography variant="h6">Beneficiary</Typography>
      <FormControl fullWidth>
        <TextField
          id="create-beneficiary"
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
          onChange={e => {
            const newValue = e.target.value;
            setAmount(newValue);

            try {
              const decimal = new Decimal(newValue);
              if (decimal.lte(0)) {
                setAmountError('Amount must be a positive number');
              } else {
                setAmountError(null);
              }
            } catch {
              setAmountError('Amount must be a positive number');
            }
          }}
          error={!!amountError}
          helperText={amountError}
        />
      </FormControl>

      <Typography variant="h6">Reason</Typography>
      <FormControl fullWidth>
        <TextField id="create-reason" value={reason} onChange={e => setReason(e.target.value)} />
      </FormControl>

      <Typography variant="h6">Can be claimed until</Typography>
      <LocalizationProvider dateAdapter={AdapterDayjs}>
        <DesktopDateTimePicker
          label={`Enter time in local timezone (${getUTCWithOffset()})`}
          value={expiresAt}
          ampm={false}
          format="YYYY-MM-DD HH:mm"
          minDateTime={dayjs()}
          readOnly={false}
          onChange={d => setExpiresAt(d ?? dayjs())}
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
        <DateWithDurationDisplay datetime={expiresAt.toDate()} enableDuration onlyDuration />
      </Typography>
    </Stack>
  );
};

export default CreateUnallocatedUnclaimedActivityRecord;
