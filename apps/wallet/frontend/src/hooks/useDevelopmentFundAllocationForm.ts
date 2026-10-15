// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useState, useMemo } from 'react';
import { useMutation, useQueryClient, UseMutationResult } from '@tanstack/react-query';
import { extractApiErrorMessage } from '@canton-network/splice-common-frontend';
import { useWalletClient } from '../contexts/WalletServiceContext';
import useGetAmuletRules from './scan-proxy/useGetAmuletRules';
import { useIsDevelopmentFundManager } from './useIsDevelopmentFundManager';
import { useUnclaimedDevelopmentFundTotal } from './useUnclaimedDevelopmentFundTotal';
import { invalidateAllDevelopmentFundQueries } from '../utils/invalidateDevelopmentFundQueries';
import BigNumber from 'bignumber.js';
import dayjs, { Dayjs } from 'dayjs';

export interface UseDevelopmentFundAllocationFormResult {
  formKey: number;
  error: string | null;
  beneficiary: string;
  setBeneficiary: (value: string) => void;
  amount: string;
  setAmount: (value: string) => void;
  expiresAt: Dayjs | null;
  setExpiresAt: (value: Dayjs | null) => void;
  mintAfter: Dayjs | null;
  setMintAfter: (value: Dayjs | null) => void;
  minMintAfter: Dayjs | null;
  isMintAfterRequired: boolean;
  isMintAfterValid: boolean;
  mintAfterError: string | undefined;
  reason: string;
  setReason: (value: string) => void;
  amountNum: BigNumber | null;
  isAmountValid: boolean;
  amountExceedsAvailable: boolean;
  isExpiryValid: boolean;
  expiryError: string | undefined;
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
  mintAfter?: Date;
}

const SUBMISSION_HEADROOM_HOURS = 1;

export const useDevelopmentFundAllocationForm = (): UseDevelopmentFundAllocationFormResult => {
  const { allocateDevelopmentFundCoupon } = useWalletClient();
  const { isFundManager } = useIsDevelopmentFundManager();
  const { data: unclaimedTotal } = useUnclaimedDevelopmentFundTotal();
  const { data: amuletRulesData } = useGetAmuletRules();
  const queryClient = useQueryClient();

  const [formKey, setFormKey] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [beneficiary, setBeneficiary] = useState('');
  const [amount, setAmount] = useState('');
  const [expiresAt, setExpiresAt] = useState<Dayjs | null>(null);
  const [mintAfterOverride, setMintAfterOverride] = useState<Dayjs | null | undefined>(undefined);
  const [defaultBaseTime, setDefaultBaseTime] = useState<Dayjs>(() => dayjs());
  const [reason, setReason] = useState('');

  const minMintingDelayMicros =
    amuletRulesData?.contract.payload.configSchedule.initialValue.minDevelopmentFundMintingDelay
      ?.microseconds;
  const isMintAfterRequired = minMintingDelayMicros !== undefined;
  const { minMintAfter, defaultMintAfter } = useMemo(() => {
    if (minMintingDelayMicros === undefined) {
      return { minMintAfter: null, defaultMintAfter: null };
    }
    const earliest = defaultBaseTime.add(Number(minMintingDelayMicros) / 1000, 'millisecond');
    return {
      minMintAfter: earliest,
      defaultMintAfter: earliest.add(SUBMISSION_HEADROOM_HOURS, 'hour'),
    };
  }, [minMintingDelayMicros, defaultBaseTime]);

  const mintAfter = mintAfterOverride !== undefined ? mintAfterOverride : defaultMintAfter;
  const setMintAfter = (value: Dayjs | null) => setMintAfterOverride(value);

  const amountNum = useMemo(() => (amount ? new BigNumber(amount) : null), [amount]);
  const isAmountValid = amountNum !== null && amountNum.isFinite() && amountNum.gt(0);
  const amountExceedsAvailable = isAmountValid && amountNum.gt(unclaimedTotal);
  const isExpiryValid = expiresAt != null && expiresAt.isValid() && expiresAt.isAfter(dayjs());
  const expiryError =
    expiresAt != null
      ? !expiresAt.isValid()
        ? 'Invalid date'
        : !expiresAt.isAfter(dayjs())
          ? 'Expiry must be in the future'
          : undefined
      : undefined;
  const isReasonValid = reason.trim().length > 0;

  const mintAfterError = (() => {
    if (mintAfter == null) {
      return isMintAfterRequired ? 'Mint after is required' : undefined;
    }
    if (!mintAfter.isValid()) {
      return 'Invalid date';
    }
    if (minMintAfter != null && mintAfter.isBefore(minMintAfter)) {
      return `Mint after must be at or after ${minMintAfter.format('MMM D, YYYY hh:mm A')}`;
    }
    if (!mintAfter.isAfter(dayjs())) {
      return 'Mint after must be in the future';
    }
    if (expiresAt != null && expiresAt.isValid() && !mintAfter.isBefore(expiresAt)) {
      return 'Mint after must be before the expiry';
    }
    return undefined;
  })();
  const isMintAfterValid = mintAfterError === undefined;

  const isValid =
    Boolean(beneficiary) &&
    isAmountValid &&
    !amountExceedsAvailable &&
    isExpiryValid &&
    isMintAfterValid &&
    isReasonValid;

  const resetForm = () => {
    setError(null);
    setBeneficiary('');
    setAmount('');
    setExpiresAt(null);
    setMintAfterOverride(undefined);
    setDefaultBaseTime(dayjs());
    setReason('');
    setFormKey(prev => prev + 1);
  };

  const allocateMutation = useMutation({
    mutationFn: async (data: AllocationPayload) => {
      return allocateDevelopmentFundCoupon(
        data.beneficiary,
        data.amount,
        data.expiresAt,
        data.reason,
        data.mintAfter
      );
    },
    onSuccess: () => {
      resetForm();
      void invalidateAllDevelopmentFundQueries(queryClient);
    },
    onError: err => {
      console.error('Failed to allocate development fund coupon', err);
      setError(extractApiErrorMessage(err));
    },
  });

  return {
    formKey,
    error,
    beneficiary,
    setBeneficiary,
    amount,
    setAmount,
    expiresAt,
    setExpiresAt,
    mintAfter,
    setMintAfter,
    minMintAfter,
    isMintAfterRequired,
    isMintAfterValid,
    mintAfterError,
    reason,
    setReason,
    amountNum,
    isAmountValid,
    amountExceedsAvailable,
    isExpiryValid,
    expiryError,
    isReasonValid,
    isValid,
    resetForm,
    allocateMutation,
    isFundManager,
    unclaimedTotal,
  };
};
