// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

export const DEVELOPMENT_FUND_QUERY_KEYS = {
  activeCoupons: ['activeDevelopmentFundCoupons'] as const,
  couponHistory: ['developmentFundCouponHistory'] as const,
  unclaimedTotal: ['scan-api', 'listUnclaimedDevelopmentFundCoupons', 'total'] as const,
} as const;
