// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { ArchivedDevelopmentFundCouponStatusEnum } from '../models/models';

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
