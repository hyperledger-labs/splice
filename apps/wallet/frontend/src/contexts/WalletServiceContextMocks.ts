// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import BigNumber from 'bignumber.js';
import { DevelopmentFundCoupon } from '../models/models';

/**
 * Mock implementations for Development Fund API methods.
 * These will be replaced with real API calls when PR-3.1 and PR-3.2 are ready.
 */

export const mockGetDevelopmentFundTotal = async (): Promise<BigNumber> => {
  return new BigNumber('198713.7612');
};

export const mockAllocateDevelopmentFundCoupon = async (
  _beneficiary: string,
  _amount: BigNumber,
  _expiresAt: Date,
  _reason: string
): Promise<void> => {
  await new Promise(resolve => setTimeout(resolve, 500));
};

export const mockListActiveDevelopmentFundCoupons = async (
  _pageSize: number = 10,
  _beginAfterId?: string
): Promise<{ coupons: DevelopmentFundCoupon[]; nextPageToken?: string }> => {
  const mockCoupons: DevelopmentFundCoupon[] = [
    {
      id: 'coupon-1',
      createdAt: new Date('2024-06-01'),
      beneficiary: 'partyA',
      amount: new BigNumber('10.5'),
      expiresAt: new Date('2024-06-05'),
      reason: 'test',
      status: 'active',
    },
  ];
  return { coupons: mockCoupons };
};

const generateAllMockCoupons = (): DevelopmentFundCoupon[] => {
  return [
    {
      id: 'coupon-1',
      createdAt: new Date('2024-06-01'),
      beneficiary: 'partyA',
      amount: new BigNumber('10.5'),
      expiresAt: new Date('2024-06-05'),
      reason: 'Security audit for protocol upgrade',
      status: 'active',
    },
    {
      id: 'coupon-2',
      createdAt: new Date('2024-06-02'),
      beneficiary: 'partyB',
      amount: new BigNumber('25.0'),
      expiresAt: new Date('2024-06-10'),
      reason: 'Performance optimization work',
      status: 'claimed',
    },
    {
      id: 'coupon-3',
      createdAt: new Date('2024-06-03'),
      beneficiary: 'partyC',
      amount: new BigNumber('15.75'),
      expiresAt: new Date('2024-06-08'),
      reason: 'Documentation improvements',
      status: 'expired',
    },
    {
      id: 'coupon-4',
      createdAt: new Date('2024-06-04'),
      beneficiary: 'partyD',
      amount: new BigNumber('50.0'),
      expiresAt: new Date('2024-06-15'),
      reason: 'Core feature development',
      status: 'withdrawn',
      withdrawalReason: 'Project cancelled',
    },
    {
      id: 'coupon-5',
      createdAt: new Date('2024-06-05'),
      beneficiary: 'partyE',
      amount: new BigNumber('30.0'),
      expiresAt: new Date('2024-06-20'),
      reason: 'Validator onboarding support',
      status: 'active',
    },
    {
      id: 'coupon-6',
      createdAt: new Date('2024-06-06'),
      beneficiary: 'partyF',
      amount: new BigNumber('12.5'),
      expiresAt: new Date('2024-06-12'),
      reason: 'Bug fixes and maintenance',
      status: 'claimed',
    },
    {
      id: 'coupon-7',
      createdAt: new Date('2024-06-07'),
      beneficiary: 'partyG',
      amount: new BigNumber('8.25'),
      expiresAt: new Date('2024-06-09'),
      reason: 'Testing infrastructure',
      status: 'expired',
    },
    {
      id: 'coupon-8',
      createdAt: new Date('2024-06-08'),
      beneficiary: 'partyH',
      amount: new BigNumber('100.0'),
      expiresAt: new Date('2024-06-25'),
      reason: 'Major protocol upgrade',
      status: 'active',
    },
    {
      id: 'coupon-9',
      createdAt: new Date('2024-06-09'),
      beneficiary: 'partyI',
      amount: new BigNumber('20.0'),
      expiresAt: new Date('2024-06-18'),
      reason: 'Community outreach',
      status: 'withdrawn',
      withdrawalReason: 'Scope changed',
    },
    {
      id: 'coupon-10',
      createdAt: new Date('2024-06-10'),
      beneficiary: 'partyJ',
      amount: new BigNumber('45.5'),
      expiresAt: new Date('2024-06-22'),
      reason: 'API improvements',
      status: 'claimed',
    },
    {
      id: 'coupon-11',
      createdAt: new Date('2024-06-11'),
      beneficiary: 'partyK',
      amount: new BigNumber('18.75'),
      expiresAt: new Date('2024-06-16'),
      reason: 'Network monitoring tools',
      status: 'active',
    },
    {
      id: 'coupon-12',
      createdAt: new Date('2024-06-12'),
      beneficiary: 'partyL',
      amount: new BigNumber('35.0'),
      expiresAt: new Date('2024-06-19'),
      reason: 'Security enhancements',
      status: 'expired',
    },
    {
      id: 'coupon-13',
      createdAt: new Date('2024-06-13'),
      beneficiary: 'partyM',
      amount: new BigNumber('22.5'),
      expiresAt: new Date('2024-06-21'),
      reason: 'Developer tooling',
      status: 'claimed',
    },
    {
      id: 'coupon-14',
      createdAt: new Date('2024-06-14'),
      beneficiary: 'partyN',
      amount: new BigNumber('60.0'),
      expiresAt: new Date('2024-06-28'),
      reason: 'Infrastructure scaling',
      status: 'active',
    },
    {
      id: 'coupon-15',
      createdAt: new Date('2024-06-15'),
      beneficiary: 'partyO',
      amount: new BigNumber('14.25'),
      expiresAt: new Date('2024-06-17'),
      reason: 'Code review process',
      status: 'withdrawn',
      withdrawalReason: 'Duplicate request',
    },
    {
      id: 'coupon-16',
      createdAt: new Date('2024-06-16'),
      beneficiary: 'partyP',
      amount: new BigNumber('40.0'),
      expiresAt: new Date('2024-06-24'),
      reason: 'Compliance updates',
      status: 'claimed',
    },
    {
      id: 'coupon-17',
      createdAt: new Date('2024-06-17'),
      beneficiary: 'partyQ',
      amount: new BigNumber('28.5'),
      expiresAt: new Date('2024-06-26'),
      reason: 'User experience improvements',
      status: 'active',
    },
    {
      id: 'coupon-18',
      createdAt: new Date('2024-06-18'),
      beneficiary: 'partyR',
      amount: new BigNumber('16.0'),
      expiresAt: new Date('2024-06-23'),
      reason: 'Integration testing',
      status: 'expired',
    },
    {
      id: 'coupon-19',
      createdAt: new Date('2024-06-19'),
      beneficiary: 'partyS',
      amount: new BigNumber('55.75'),
      expiresAt: new Date('2024-06-30'),
      reason: 'Protocol governance',
      status: 'active',
    },
    {
      id: 'coupon-20',
      createdAt: new Date('2024-06-20'),
      beneficiary: 'partyT',
      amount: new BigNumber('32.0'),
      expiresAt: new Date('2024-06-27'),
      reason: 'Performance benchmarking',
      status: 'claimed',
    },
    {
      id: 'coupon-21',
      createdAt: new Date('2024-06-21'),
      beneficiary: 'partyU',
      amount: new BigNumber('19.5'),
      expiresAt: new Date('2024-06-29'),
      reason: 'Data analytics dashboard',
      status: 'withdrawn',
      withdrawalReason: 'Budget reallocation',
    },
    {
      id: 'coupon-22',
      createdAt: new Date('2024-06-22'),
      beneficiary: 'partyV',
      amount: new BigNumber('42.25'),
      expiresAt: new Date('2024-07-01'),
      reason: 'Mobile app development',
      status: 'active',
    },
    {
      id: 'coupon-23',
      createdAt: new Date('2024-06-23'),
      beneficiary: 'partyW',
      amount: new BigNumber('26.0'),
      expiresAt: new Date('2024-07-02'),
      reason: 'Backend optimization',
      status: 'claimed',
    },
    {
      id: 'coupon-24',
      createdAt: new Date('2024-06-24'),
      beneficiary: 'partyX',
      amount: new BigNumber('38.5'),
      expiresAt: new Date('2024-07-03'),
      reason: 'Frontend refactoring',
      status: 'expired',
    },
    {
      id: 'coupon-25',
      createdAt: new Date('2024-06-25'),
      beneficiary: 'partyY',
      amount: new BigNumber('75.0'),
      expiresAt: new Date('2024-07-05'),
      reason: 'Multi-signature support',
      status: 'active',
    },
  ];
};

export const mockListDevelopmentFundCouponsHistory = async (
  pageSize: number = 10,
  offset: number = 0
): Promise<{ coupons: DevelopmentFundCoupon[]; total: number }> => {
  // Mock: Generate a larger set of sample coupons with various statuses
  const allMockCoupons = generateAllMockCoupons();

  const sortedCoupons = [...allMockCoupons].sort(
    (a, b) => b.createdAt.getTime() - a.createdAt.getTime()
  );

  const pageCoupons = sortedCoupons.slice(offset, offset + pageSize);
  const total = sortedCoupons.length;

  return { coupons: pageCoupons, total };
};

export const mockWithdrawDevelopmentFundCoupon = async (
  _couponId: string,
  _reason: string
): Promise<void> => {
  // Mock: In real implementation, this would call walletClient.withdrawDevelopmentFundCoupon
  // For now, we just simulate a successful withdrawal
  await new Promise(resolve => setTimeout(resolve, 500));
};
