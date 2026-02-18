// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { ArchivedDevelopmentFundCouponStatusEnum } from '@lfdecentralizedtrust/wallet-openapi';

export const formatDate = (date: Date): string => {
  return date.toLocaleDateString('en-US', { month: 'long', day: 'numeric', year: 'numeric' });
};

export const formatDateTime = (date: Date): string => {
  return date.toLocaleDateString('en-US', {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
};

export const getDevelopmentFundEventTypeLabel = (
  status: ArchivedDevelopmentFundCouponStatusEnum
): string => {
  switch (status) {
    case 'claimed':
      return 'Claimed';
    case 'withdrawn':
      return 'Withdrawn';
    case 'rejected':
      return 'Rejected';
    case 'expired':
      return 'Expired';
    default:
      return status;
  }
};
