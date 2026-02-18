// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useState, useMemo } from 'react';
import { useMutation, useQueryClient, UseMutationResult } from '@tanstack/react-query';
import { useWalletClient } from '../contexts/WalletServiceContext';
import { useIsDevelopmentFundManager } from './useIsDevelopmentFundManager';
import { useUnclaimedDevelopmentFundTotal } from './useUnclaimedDevelopmentFundTotal';
import { invalidateAllDevelopmentFundQueries } from '../utils/invalidateDevelopmentFundQueries';
import BigNumber from 'bignumber.js';
import dayjs, { Dayjs } from 'dayjs';

export interface UseDevelopmentFundAllocationFormResult {
  formKey: number;
  error: unknown;
  setError: (error: unknown) => void;
  beneficiary: string;
  setBeneficiary: (value: string) => void;
  amount: string;
  setAmount: (value: string) => void;
  expiresAt: Dayjs | null;
  setExpiresAt: (value: Dayjs | null) => void;
  reason: string;
  setReason: (value: string) => void;
  amountNum: BigNumber | null;
  isAmountValid: boolean;
  amountExceedsAvailable: boolean;
  isExpiryValid: boolean;
  isReasonValid: boolean;
  isValid: boolean;
  resetForm: () => void;
  allocateMutation: UseMutationResult<void, Error, AllocationPayload>;
  isFundManager: boolean;
  unclaimedTotal: BigNumber;
}

interface AllocationPayload {
  beneficiary: string;
  amount: BigNumber;
  expiresAt: Date;
  reason: string;
}

export const useDevelopmentFundAllocationForm = (): UseDevelopmentFundAllocationFormResult => {
  const { allocateDevelopmentFundCoupon } = useWalletClient();
  const { isFundManager } = useIsDevelopmentFundManager();
  const { data: unclaimedTotal } = useUnclaimedDevelopmentFundTotal();
  const queryClient = useQueryClient();

  const [formKey, setFormKey] = useState(0);
  const [error, setError] = useState<unknown>(null);
  const [beneficiary, setBeneficiary] = useState('');
  const [amount, setAmount] = useState('');
  const [expiresAt, setExpiresAt] = useState<Dayjs | null>(null);
  const [reason, setReason] = useState('');

  const amountNum = useMemo(() => (amount ? new BigNumber(amount) : null), [amount]);
  const isAmountValid =
    amountNum !== null && amountNum.isFinite() && amountNum.gt(0);
  const amountExceedsAvailable = isAmountValid && amountNum.gt(unclaimedTotal);
  const isExpiryValid = expiresAt?.isAfter(dayjs()) ?? false;
  const isReasonValid = reason.trim().length > 0;
  const isValid =
    Boolean(beneficiary) &&
    isAmountValid &&
    !amountExceedsAvailable &&
    isExpiryValid &&
    isReasonValid;

  const resetForm = () => {
    setError(null);
    setBeneficiary('');
    setAmount('');
    setExpiresAt(null);
    setReason('');
    setFormKey(prev => prev + 1);
  };

  const allocateMutation = useMutation({
    mutationFn: async (data: AllocationPayload) => {
      return allocateDevelopmentFundCoupon(
        data.beneficiary,
        data.amount,
        data.expiresAt,
        data.reason
      );
    },
    onSuccess: () => {
      resetForm();
      invalidateAllDevelopmentFundQueries(queryClient);
    },
    onError: err => {
      console.error('Failed to allocate development fund coupon', err);
      setError(err);
    },
  });

  return {
    formKey,
    error,
    setError,
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
    isExpiryValid,
    isReasonValid,
    isValid,
    resetForm,
    allocateMutation,
    isFundManager,
    unclaimedTotal,
  };
};
