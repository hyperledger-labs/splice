// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React, { useState } from 'react';
import { useWalletClient } from '../contexts/WalletServiceContext';
import { useMutation } from '@tanstack/react-query';
import {
  AllocateAmuletRequest,
  AllocateAmuletRequestSettlement,
  AllocateAmuletRequestTransferLeg,
} from 'wallet-openapi';
import { Alert, Button, Card, CardContent, Stack, TextField, Typography } from '@mui/material';
import { DisableConditionally } from '@lfdecentralizedtrust/splice-common-frontend';
import BftAnsField from './BftAnsField';
import AmountInput from './AmountInput';
import { Add, Remove } from '@mui/icons-material';
import { DesktopDateTimePicker } from '@mui/x-date-pickers/DesktopDateTimePicker';
import dayjs from 'dayjs';
import { getUTCWithOffset } from '@lfdecentralizedtrust/splice-common-frontend-utils';

const CreateAllocation: React.FC = () => {
  const { createAllocation } = useWalletClient();
  const [error, setError] = useState<object | null>(null);
  const [allocation, setAllocation] = useState<PartialAllocateAmuletRequest>(emptyForm());
  const validated = validatedForm(allocation);
  const createAllocationMutation = useMutation({
    mutationFn: async () => {
      return validated && (await createAllocation(validated));
    },
    onSuccess: () => {
      setError(null);
      setAllocation(emptyForm());
    },
    onError: error => {
      console.error('Failed to submit allocation', error);
      setError(error);
    },
  });

  return (
    <Stack mt={4} spacing={4} direction="column" justifyContent="center">
      <Typography mt={6} variant="h4">
        Create Allocation
      </Typography>
      <Card variant="outlined">
        <CardContent sx={{ paddingX: '64px' }}>
          <Stack spacing={1}>
            {error ? (
              <Alert severity="error">Failed to submit: {JSON.stringify(error)}</Alert>
            ) : null}
            <Typography variant="h6">Transfer Leg ID</Typography>
            <TextField
              id="create-allocation-transfer-leg-id"
              value={allocation.transfer_leg_id}
              error={!allocation.transfer_leg_id}
              onChange={event =>
                setAllocation({ ...allocation, transfer_leg_id: event.target.value })
              }
            />
            <Typography variant="h6">Settlement Ref</Typography>
            <Stack direction="row" alignItems="center" spacing={2}>
              <Typography variant="h6">ID</Typography>
              <TextField
                id="create-allocation-settlement-ref-id"
                value={allocation.settlement.settlement_ref?.id || ''}
                error={!allocation.settlement.settlement_ref?.id}
                onChange={event =>
                  setAllocation({
                    ...allocation,
                    settlement: {
                      ...allocation.settlement,
                      settlement_ref: {
                        id: event.target.value,
                        cid: allocation.settlement.settlement_ref?.cid,
                      },
                    },
                  })
                }
              />
              <Typography variant="h6">Contract ID (optional)</Typography>
              <TextField
                id="create-allocation-settlement-ref-cid"
                value={allocation.settlement.settlement_ref?.cid || ''}
                onChange={event =>
                  setAllocation({
                    ...allocation,
                    settlement: {
                      ...allocation.settlement,
                      settlement_ref: {
                        id: allocation.settlement.settlement_ref?.id || '',
                        cid: event.target.value || undefined,
                      },
                    },
                  })
                }
              />
            </Stack>
            <Typography variant="h6">Recipient</Typography>
            <BftAnsField
              name="Receiver"
              label="Receiver"
              aria-label="Receiver"
              id="create-allocation-transfer-leg-receiver"
              onPartyChanged={party =>
                setAllocation({
                  ...allocation,
                  transfer_leg: { ...allocation.transfer_leg, receiver: party },
                })
              }
            />
            <Typography variant="h6">Executor</Typography>
            <BftAnsField
              name="Executor"
              label="Executor"
              aria-label="Executor"
              id="create-allocation-settlement-executor"
              onPartyChanged={party =>
                setAllocation({
                  ...allocation,
                  settlement: { ...allocation.settlement, executor: party },
                })
              }
            />
            <AmountInput
              idPrefix="create-allocation"
              ccAmountText={allocation.transfer_leg.amount || ''}
              setCcAmountText={ccAmountText =>
                setAllocation({
                  ...allocation,
                  transfer_leg: { ...allocation.transfer_leg, amount: ccAmountText },
                })
              }
            />
            <Typography variant="h6">Settle before</Typography>
            <DesktopDateTimePicker
              label={`Enter time in local timezone (${getUTCWithOffset()})`}
              value={dayjs((allocation.settlement.settle_before || 0) / 1000)}
              format="YYYY-MM-DD HH:mm"
              minDate={dayjs()}
              readOnly={false}
              onChange={newValue => {
                if (newValue) {
                  const micros = newValue.second(0).unix() * 1000 * 1000;
                  setAllocation({
                    ...allocation,
                    settlement: { ...allocation.settlement, settle_before: micros },
                  });
                }
              }}
              slotProps={{
                textField: {
                  id: 'create-allocation-settlement-settle-before',
                },
              }}
              closeOnSelect
            />
            <Typography variant="h6">Allocate before</Typography>
            <DesktopDateTimePicker
              label={`Enter time in local timezone (${getUTCWithOffset()})`}
              value={dayjs((allocation.settlement.allocate_before || 0) / 1000)}
              format="YYYY-MM-DD HH:mm"
              minDate={dayjs()}
              readOnly={false}
              onChange={newValue => {
                if (newValue) {
                  const micros = newValue.second(0).unix() * 1000 * 1000;
                  setAllocation({
                    ...allocation,
                    settlement: { ...allocation.settlement, allocate_before: micros },
                  });
                }
              }}
              slotProps={{
                textField: {
                  id: 'create-allocation-settlement-allocate-before',
                },
              }}
              closeOnSelect
            />
            <Typography variant="h6">Settlement meta</Typography>
            <MetaEditor
              idPrefix="settlement"
              meta={allocation.settlement.meta || {}}
              setMeta={meta =>
                setAllocation({
                  ...allocation,
                  settlement: { ...allocation.settlement, meta: meta },
                })
              }
            />
            <Typography variant="h6">Transfer leg meta</Typography>
            <MetaEditor
              idPrefix="transfer-leg"
              meta={allocation.transfer_leg.meta || {}}
              setMeta={meta =>
                setAllocation({
                  ...allocation,
                  transfer_leg: { ...allocation.transfer_leg, meta: meta },
                })
              }
            />
            <DisableConditionally
              conditions={[
                {
                  disabled: createAllocationMutation.isPending,
                  reason: 'Creating allocation...',
                },
                {
                  disabled: !validated,
                  reason: 'Form is not valid, please check the fields.',
                },
              ]}
            >
              <Button
                id="create-allocation-submit-button"
                variant="pill"
                fullWidth
                size="large"
                onClick={() => createAllocationMutation.mutate()}
              >
                Send
              </Button>
            </DisableConditionally>
          </Stack>
        </CardContent>
      </Card>
    </Stack>
  );
};

interface PartialAllocateAmuletRequest {
  settlement: Partial<AllocateAmuletRequestSettlement>;
  transfer_leg_id: string;
  transfer_leg: Partial<AllocateAmuletRequestTransferLeg>;
}

function emptyForm(): PartialAllocateAmuletRequest {
  const nowMicros = dayjs().unix() * 1000 * 1000;
  return {
    settlement: {
      executor: '',
      meta: {},
      allocate_before: nowMicros,
      settle_before: nowMicros,
      settlement_ref: {
        id: '',
        cid: undefined,
      },
    },
    transfer_leg_id: '',
    transfer_leg: {
      amount: '1',
      receiver: '',
      meta: {},
    },
  };
}

function validatedForm(partial: PartialAllocateAmuletRequest): AllocateAmuletRequest | null {
  if (
    !partial.settlement.executor ||
    !partial.settlement.settlement_ref?.id ||
    !partial.settlement.allocate_before ||
    !partial.settlement.settle_before ||
    !partial.transfer_leg_id ||
    !partial.transfer_leg.amount ||
    !partial.transfer_leg.receiver
  ) {
    return null;
  }
  return {
    settlement: {
      executor: partial.settlement.executor,
      meta: partial.settlement.meta,
      allocate_before: partial.settlement.allocate_before,
      settle_before: partial.settlement.settle_before,
      settlement_ref: {
        id: partial.settlement.settlement_ref.id,
        cid: partial.settlement.settlement_ref.cid,
      },
    },
    transfer_leg_id: partial.transfer_leg_id,
    transfer_leg: {
      amount: partial.transfer_leg.amount,
      receiver: partial.transfer_leg.receiver,
      meta: partial.transfer_leg.meta,
    },
  };
}

export default CreateAllocation;

type Meta = { [key: string]: string };
const MetaEditor: React.FC<{ meta: Meta; setMeta: (meta: Meta) => void; idPrefix: string }> = ({
  meta,
  setMeta,
  idPrefix,
}) => {
  const keys = Object.keys(meta);
  // will always have at least keys(meta).length
  const [nEntries, setNEntries] = useState(keys.length);
  const emptyEntries: string[] = Array.from({ length: nEntries - keys.length }).map(() => '');
  const allEntries = keys.concat(emptyEntries);
  const deleteEntry = (key: string) => {
    const newMeta = { ...meta };
    delete newMeta[key];
    setMeta(newMeta);
    setNEntries(nEntries - 1);
  };
  return (
    <Stack direction="column">
      {allEntries.map((k, idx) => {
        const key = k || '';
        const value = meta[key] || '';
        const updateKey = (newKey: string) => {
          const newMeta = { ...meta };
          delete newMeta[key];
          newMeta[newKey] = value;
          setMeta(newMeta);
        };
        const updateValue = (newValue: string) => setMeta({ ...meta, [key]: newValue });
        return (
          <Stack direction="row" key={idx}>
            <TextField
              id={`${idPrefix}-meta-key-${idx}`}
              placeholder="key"
              value={key}
              onChange={event => updateKey(event.target.value)}
            />
            <TextField
              id={`${idPrefix}-meta-value-${idx}`}
              placeholder="value"
              value={value}
              onChange={event => updateValue(event.target.value)}
            />
            <Button startIcon={<Remove />} onClick={() => deleteEntry(key)} />
          </Stack>
        );
      })}
      <Button
        id={`${idPrefix}-add-meta`}
        startIcon={<Add />}
        onClick={() => setNEntries(nEntries + 1)}
      >
        Add Entry
      </Button>
    </Stack>
  );
};
