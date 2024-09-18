// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useMutation, UseMutationResult } from '@tanstack/react-query';
import BigNumber from 'bignumber.js';

import { useWalletClient } from '../contexts/WalletServiceContext';

export const useTap: () => UseMutationResult<void, unknown, BigNumber> = () => {
  const { tap } = useWalletClient();

  return useMutation({
    mutationFn: (amount: BigNumber) => {
      const strVal = amount.isInteger() ? amount.toFixed(1) : amount.toString();
      return tap(strVal);
    },
    onError: error => {
      console.log('Error occurred during tap', error);
    },
  });
};
