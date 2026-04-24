// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React, { useState } from 'react';
import { useWalletClient } from '../contexts/WalletServiceContext';
import { useMutation } from '@tanstack/react-query';
import {
  AllocateAmuletV2Request,
  AllocateAmuletRequestSettlementSettlementRef,
  TransferLegV2,
} from '@lfdecentralizedtrust/wallet-openapi';
import {
  Alert,
  Button,
  Card,
  CardContent,
  Divider,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { DisableConditionally } from '@lfdecentralizedtrust/splice-common-frontend';
import BftAnsField from './BftAnsField';
import AmountInput from './AmountInput';
import { Add, Remove } from '@mui/icons-material';
import {
  DAML_TIMESTAMP_FORMAT,
  damlTimestampToOpenApiTimestamp,
  isValidDamlTimestamp,
} from '../utils/timestampConversion';

const CreateAllocation: React.FC = () => {
  const { createAllocationV2 } = useWalletClient();
  const [error, setError] = useState<object | null>(null);
  const [allocation, setAllocation] = useState<PartialAllocateAmuletV2Request>(emptyForm());
  const validated = validatedForm(allocation);
  const createAllocationMutation = useMutation({
    mutationFn: async () => {
      return validated && (await createAllocationV2(validated));
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

  const updateExecutor = (idx: number, party: string) => {
    const newExecutors = [...allocation.settlement.executors];
    newExecutors[idx] = party;
    setAllocation({
      ...allocation,
      settlement: { ...allocation.settlement, executors: newExecutors },
    });
  };

  const addExecutor = () => {
    setAllocation({
      ...allocation,
      settlement: {
        ...allocation.settlement,
        executors: [...allocation.settlement.executors, ''],
      },
    });
  };

  const removeExecutor = (idx: number) => {
    const newExecutors = allocation.settlement.executors.filter((_, i) => i !== idx);
    setAllocation({
      ...allocation,
      settlement: {
        ...allocation.settlement,
        executors: newExecutors.length > 0 ? newExecutors : [''],
      },
    });
  };

  const updateLeg = (idx: number, updated: Partial<PartialTransferLeg>) => {
    const newLegs = [...allocation.transfer_legs];
    newLegs[idx] = { ...newLegs[idx], ...updated };
    setAllocation({ ...allocation, transfer_legs: newLegs });
  };

  const addLeg = () => {
    setAllocation({
      ...allocation,
      transfer_legs: [...allocation.transfer_legs, emptyTransferLeg()],
    });
  };

  const removeLeg = (idx: number) => {
    const newLegs = allocation.transfer_legs.filter((_, i) => i !== idx);
    setAllocation({
      ...allocation,
      transfer_legs: newLegs.length > 0 ? newLegs : [emptyTransferLeg()],
    });
  };

  return (
    <Stack mt={4} spacing={4} direction="column" justifyContent="center">
      <Typography mt={6} variant="h4">
        Create Allocation
      </Typography>
      <Card variant="outlined">
        <CardContent sx={{ paddingX: '64px' }}>
          <Stack spacing={2}>
            {error ? (
              <Alert severity="error">Failed to submit: {JSON.stringify(error)}</Alert>
            ) : null}

            <Typography variant="h5">Settlement</Typography>
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
            <Typography variant="h6">Executors</Typography>
            {allocation.settlement.executors.map((_executor, idx) => (
              <Stack key={idx} direction="row" alignItems="center" spacing={1}>
                <BftAnsField
                  name={`Executor ${idx}`}
                  label={`Executor ${idx + 1}`}
                  aria-label={`Executor ${idx}`}
                  id={`create-allocation-settlement-executor-${idx}`}
                  onPartyChanged={party => updateExecutor(idx, party)}
                />
                {allocation.settlement.executors.length > 1 && (
                  <Button
                    startIcon={<Remove />}
                    color="error"
                    size="small"
                    onClick={() => removeExecutor(idx)}
                  >
                    Remove
                  </Button>
                )}
              </Stack>
            ))}
            <Button startIcon={<Add />} size="small" onClick={addExecutor}>
              Add Executor
            </Button>
            <Typography variant="h6">Requested at ({DAML_TIMESTAMP_FORMAT})</Typography>
            <TextField
              id="create-allocation-settlement-requested-at"
              placeholder={DAML_TIMESTAMP_FORMAT}
              value={allocation.settlement.requested_at || ''}
              error={!isValidDamlTimestamp(allocation.settlement.requested_at)}
              onChange={event =>
                setAllocation({
                  ...allocation,
                  settlement: { ...allocation.settlement, requested_at: event.target.value },
                })
              }
            />
            <Typography variant="h6">Settle at ({DAML_TIMESTAMP_FORMAT})</Typography>
            <TextField
              id="create-allocation-settlement-settle-at"
              placeholder={DAML_TIMESTAMP_FORMAT}
              value={allocation.settlement.settle_at || ''}
              error={!isValidDamlTimestamp(allocation.settlement.settle_at)}
              onChange={event =>
                setAllocation({
                  ...allocation,
                  settlement: { ...allocation.settlement, settle_at: event.target.value },
                })
              }
            />
            <Typography variant="h6">
              Settlement deadline (optional, {DAML_TIMESTAMP_FORMAT})
            </Typography>
            <TextField
              id="create-allocation-settlement-deadline"
              placeholder={DAML_TIMESTAMP_FORMAT}
              value={allocation.settlement.settlement_deadline || ''}
              error={
                !!allocation.settlement.settlement_deadline &&
                !isValidDamlTimestamp(allocation.settlement.settlement_deadline)
              }
              onChange={event =>
                setAllocation({
                  ...allocation,
                  settlement: {
                    ...allocation.settlement,
                    settlement_deadline: event.target.value || undefined,
                  },
                })
              }
            />

            <Divider />
            <Typography variant="h5">Transfer Legs</Typography>
            {allocation.transfer_legs.map((leg, idx) => (
              <Card key={idx} variant="outlined" sx={{ p: 2 }}>
                <Stack spacing={1}>
                  <Stack direction="row" justifyContent="space-between" alignItems="center">
                    <Typography variant="h6">Transfer Leg {idx + 1}</Typography>
                    {allocation.transfer_legs.length > 1 && (
                      <Button
                        startIcon={<Remove />}
                        color="error"
                        size="small"
                        onClick={() => removeLeg(idx)}
                      >
                        Remove
                      </Button>
                    )}
                  </Stack>
                  <Typography variant="body2">Transfer Leg ID</Typography>
                  <TextField
                    id={`create-allocation-transfer-leg-id-${idx}`}
                    value={leg.transfer_leg_id}
                    error={!leg.transfer_leg_id}
                    onChange={event => updateLeg(idx, { transfer_leg_id: event.target.value })}
                  />
                  <Typography variant="body2">Sender</Typography>
                  <BftAnsField
                    name={`Sender ${idx}`}
                    label="Sender"
                    aria-label={`Sender ${idx}`}
                    id={`create-allocation-transfer-leg-sender-${idx}`}
                    onPartyChanged={party => updateLeg(idx, { sender: party })}
                  />
                  <Typography variant="body2">Receiver</Typography>
                  <BftAnsField
                    name={`Receiver ${idx}`}
                    label="Receiver"
                    aria-label={`Receiver ${idx}`}
                    id={`create-allocation-transfer-leg-receiver-${idx}`}
                    onPartyChanged={party => updateLeg(idx, { receiver: party })}
                  />
                  <AmountInput
                    idPrefix={`create-allocation-${idx}`}
                    ccAmountText={leg.amount}
                    setCcAmountText={amount => updateLeg(idx, { amount })}
                  />
                </Stack>
              </Card>
            ))}
            <Button id="add-transfer-leg" startIcon={<Add />} onClick={addLeg}>
              Add Transfer Leg
            </Button>

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

export default CreateAllocation;

interface PartialTransferLeg {
  transfer_leg_id: string;
  sender: string;
  receiver: string;
  amount: string;
}

interface PartialAllocateAmuletV2Request {
  settlement: {
    executors: string[];
    settlement_ref: AllocateAmuletRequestSettlementSettlementRef;
    requested_at: string;
    settle_at: string;
    settlement_deadline?: string;
  };
  transfer_legs: PartialTransferLeg[];
}

function emptyTransferLeg(): PartialTransferLeg {
  return { transfer_leg_id: '', sender: '', receiver: '', amount: '1' };
}

function emptyForm(): PartialAllocateAmuletV2Request {
  return {
    settlement: {
      executors: [''],
      requested_at: '',
      settle_at: '',
      settlement_deadline: undefined,
      settlement_ref: { id: '', cid: undefined },
    },
    transfer_legs: [emptyTransferLeg()],
  };
}

function validatedForm(partial: PartialAllocateAmuletV2Request): AllocateAmuletV2Request | null {
  if (
    !partial.settlement.executors.length ||
    partial.settlement.executors.some(e => !e) ||
    !partial.settlement.settlement_ref?.id ||
    ![partial.settlement.requested_at, partial.settlement.settle_at].every(isValidDamlTimestamp) ||
    (partial.settlement.settlement_deadline &&
      !isValidDamlTimestamp(partial.settlement.settlement_deadline))
  ) {
    return null;
  }
  const validLegs: TransferLegV2[] = [];
  for (const leg of partial.transfer_legs) {
    if (!leg.transfer_leg_id || !leg.sender || !leg.receiver || !leg.amount) return null;
    validLegs.push({
      transfer_leg_id: leg.transfer_leg_id,
      sender: leg.sender,
      receiver: leg.receiver,
      amount: leg.amount,
    });
  }
  if (validLegs.length === 0) return null;
  return {
    settlement: {
      executors: partial.settlement.executors,
      requested_at: damlTimestampToOpenApiTimestamp(partial.settlement.requested_at),
      settle_at: damlTimestampToOpenApiTimestamp(partial.settlement.settle_at),
      settlement_deadline: partial.settlement.settlement_deadline
        ? damlTimestampToOpenApiTimestamp(partial.settlement.settlement_deadline)
        : undefined,
      settlement_ref: {
        id: partial.settlement.settlement_ref.id,
        cid: partial.settlement.settlement_ref.cid,
      },
    },
    transfer_legs: validLegs,
  };
}
