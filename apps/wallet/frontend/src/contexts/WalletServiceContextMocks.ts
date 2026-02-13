// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import BigNumber from 'bignumber.js';
import { CouponHistoryEvent, DevelopmentFundCoupon } from '../models/models';

/**
 * Mock implementations for Development Fund API methods.
 * These will be replaced with real API calls when PR-3.1 and PR-3.2 are ready.
 */

// Mock beneficiary party IDs
const mockBeneficiaries = [
  'alice::122034a8c7f2b9e0d4c6f8a1b5e3d9f2c7a8b4e6d0f3c9a5b7e1d8f2c4a6b0e3d9f5',
  'bob::122045b9d8f3c0e1d5a7f9b2c6e4d0f3c8a9b5e7d1f4c0a6b8e2d9f3c5a7b1e4d0f6',
  'carol::122056c0e9f4d1e2d6a8f0c3d7e5d1f4c9a0b6e8d2f5c1a7b9e3d0f4c6a8b2e5d1f7',
  'david::122067d1f0f5e2e3d7a9f1c4d8e6d2f5c0a1b7e9d3f6c2a8b0e4d1f5c7a9b3e6d2f8',
  'emma::122078e2f1f6e3e4d8b0f2c5d9e7d3f6c1a2b8e0d4f7c3a9b1e5d2f6c8a0b4e7d3f9',
  'frank::122089f3f2f7e4e5d9b1f3c6d0e8d4f7c2a3b9e1d5f8c4a0b2e6d3f7c9a1b5e8d4f0',
  'grace::12209af4f3f8e5e6d0b2f4c7d1e9d5f8c3a4b0e2d6f9c5a1b3e7d4f8c0a2b6e9d5f1',
  'henry::1220abf5f4f9e6e7d1b3f5c8d2e0d6f9c4a5b1e3d7f0c6a2b4e8d5f9c1a3b7e0d6f2',
  'irene::1220bcf6f5fae7e8d2b4f6c9d3e1d7f0c5a6b2e4d8f1c7a3b5e9d6f0c2a4b8e1d7f3',
  'jack::1220cdf7f6fbe8e9d3b5f7c0d4e2d8f1c6a7b3e5d9f2c8a4b6e0d7f1c3a5b9e2d8f4',
];

const getBeneficiary = (index: number) => mockBeneficiaries[index % mockBeneficiaries.length];

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

// Generate active coupons for the active coupons table
const generateActiveCoupons = (): DevelopmentFundCoupon[] => {
  const beneficiaryData = [
    { index: 0, id: 'active-1', amount: '10.5', expiresAt: '2025-02-15', reason: 'Security audit for protocol upgrade' },
    { index: 4, id: 'active-2', amount: '30.0', expiresAt: '2025-02-20', reason: 'Validator onboarding support' },
    { index: 7, id: 'active-3', amount: '100.0', expiresAt: '2025-02-25', reason: 'Major protocol upgrade' },
    { index: 1, id: 'active-4', amount: '18.75', expiresAt: '2025-02-16', reason: 'Network monitoring tools' },
    { index: 2, id: 'active-5', amount: '60.0', expiresAt: '2025-02-28', reason: 'Infrastructure scaling' },
    { index: 5, id: 'active-6', amount: '28.5', expiresAt: '2025-02-26', reason: 'User experience improvements' },
    { index: 8, id: 'active-7', amount: '55.75', expiresAt: '2025-03-01', reason: 'Protocol governance' },
    { index: 3, id: 'active-8', amount: '42.25', expiresAt: '2025-03-05', reason: 'Mobile app development' },
    { index: 6, id: 'active-9', amount: '75.0', expiresAt: '2025-03-10', reason: 'Multi-signature support' },
    { index: 9, id: 'active-10', amount: '35.5', expiresAt: '2025-03-15', reason: 'Cross-chain integration' },
    { index: 0, id: 'active-11', amount: '22.0', expiresAt: '2025-03-20', reason: 'Smart contract auditing' },
    { index: 1, id: 'active-12', amount: '48.75', expiresAt: '2025-03-25', reason: 'Developer documentation' },
  ];

  return beneficiaryData.map((item, idx) => {
    return {
      id: item.id,
      createdAt: new Date(Date.now() - (30 - idx) * 24 * 60 * 60 * 1000),
      beneficiary: getBeneficiary(item.index),
      amount: new BigNumber(item.amount),
      expiresAt: new Date(item.expiresAt),
      reason: item.reason,
      status: 'active' as const,
    };
  });
};

export const mockListActiveDevelopmentFundCoupons = async (
  pageSize: number = 10,
  offset: number = 0
): Promise<{ coupons: DevelopmentFundCoupon[]; total: number }> => {
  const allActiveCoupons = generateActiveCoupons();

  const sortedCoupons = [...allActiveCoupons].sort(
    (a, b) => b.createdAt.getTime() - a.createdAt.getTime()
  );

  const pageCoupons = sortedCoupons.slice(offset, offset + pageSize);
  const total = sortedCoupons.length;

  return { coupons: pageCoupons, total };
};

// Generate coupon history events
const generateCouponHistoryEvents = (): CouponHistoryEvent[] => {
  const events: CouponHistoryEvent[] = [
    // Activation events
    {
      id: 'event-1',
      couponId: 'coupon-1',
      eventType: 'activation',
      timestamp: new Date('2024-12-01T10:00:00'),
      beneficiary: getBeneficiary(0),
      amount: new BigNumber('10.5'),
      allocationReason: 'Security audit for protocol upgrade',
    },
    {
      id: 'event-2',
      couponId: 'coupon-2',
      eventType: 'activation',
      timestamp: new Date('2024-12-02T14:30:00'),
      beneficiary: getBeneficiary(1),
      amount: new BigNumber('25.0'),
      allocationReason: 'Performance optimization work',
    },
    {
      id: 'event-3',
      couponId: 'coupon-2',
      eventType: 'claim',
      timestamp: new Date('2024-12-05T09:15:00'),
      beneficiary: getBeneficiary(1),
      amount: new BigNumber('25.0'),
      allocationReason: 'Performance optimization work',
    },
    {
      id: 'event-4',
      couponId: 'coupon-3',
      eventType: 'activation',
      timestamp: new Date('2024-12-03T11:45:00'),
      beneficiary: getBeneficiary(2),
      amount: new BigNumber('15.75'),
      allocationReason: 'Documentation improvements',
    },
    {
      id: 'event-5',
      couponId: 'coupon-3',
      eventType: 'expiration',
      timestamp: new Date('2024-12-08T00:00:00'),
      beneficiary: getBeneficiary(2),
      amount: new BigNumber('15.75'),
      allocationReason: 'Documentation improvements',
    },
    {
      id: 'event-6',
      couponId: 'coupon-4',
      eventType: 'activation',
      timestamp: new Date('2024-12-04T16:20:00'),
      beneficiary: getBeneficiary(3),
      amount: new BigNumber('50.0'),
      allocationReason: 'Core feature development',
    },
    {
      id: 'event-7',
      couponId: 'coupon-4',
      eventType: 'withdrawal',
      timestamp: new Date('2024-12-10T13:00:00'),
      beneficiary: getBeneficiary(3),
      amount: new BigNumber('50.0'),
      allocationReason: 'Core feature development',
      withdrawalReason: 'Project cancelled',
    },
    {
      id: 'event-8',
      couponId: 'coupon-5',
      eventType: 'activation',
      timestamp: new Date('2024-12-05T08:30:00'),
      beneficiary: getBeneficiary(4),
      amount: new BigNumber('30.0'),
      allocationReason: 'Validator onboarding support',
    },
    {
      id: 'event-9',
      couponId: 'coupon-6',
      eventType: 'activation',
      timestamp: new Date('2024-12-06T10:00:00'),
      beneficiary: getBeneficiary(5),
      amount: new BigNumber('12.5'),
      allocationReason: 'Bug fixes and maintenance',
    },
    {
      id: 'event-10',
      couponId: 'coupon-6',
      eventType: 'claim',
      timestamp: new Date('2024-12-11T15:45:00'),
      beneficiary: getBeneficiary(5),
      amount: new BigNumber('12.5'),
      allocationReason: 'Bug fixes and maintenance',
    },
    {
      id: 'event-11',
      couponId: 'coupon-7',
      eventType: 'activation',
      timestamp: new Date('2024-12-07T12:15:00'),
      beneficiary: getBeneficiary(6),
      amount: new BigNumber('8.25'),
      allocationReason: 'Testing infrastructure',
    },
    {
      id: 'event-12',
      couponId: 'coupon-7',
      eventType: 'expiration',
      timestamp: new Date('2024-12-09T00:00:00'),
      beneficiary: getBeneficiary(6),
      amount: new BigNumber('8.25'),
      allocationReason: 'Testing infrastructure',
    },
    {
      id: 'event-13',
      couponId: 'coupon-8',
      eventType: 'activation',
      timestamp: new Date('2024-12-08T09:00:00'),
      beneficiary: getBeneficiary(7),
      amount: new BigNumber('100.0'),
      allocationReason: 'Major protocol upgrade',
    },
    {
      id: 'event-14',
      couponId: 'coupon-9',
      eventType: 'activation',
      timestamp: new Date('2024-12-09T14:00:00'),
      beneficiary: getBeneficiary(8),
      amount: new BigNumber('20.0'),
      allocationReason: 'Community outreach',
    },
    {
      id: 'event-15',
      couponId: 'coupon-9',
      eventType: 'withdrawal',
      timestamp: new Date('2024-12-15T10:30:00'),
      beneficiary: getBeneficiary(8),
      amount: new BigNumber('20.0'),
      allocationReason: 'Community outreach',
      withdrawalReason: 'Scope changed',
    },
    {
      id: 'event-16',
      couponId: 'coupon-10',
      eventType: 'activation',
      timestamp: new Date('2024-12-10T11:30:00'),
      beneficiary: getBeneficiary(9),
      amount: new BigNumber('45.5'),
      allocationReason: 'API improvements',
    },
    {
      id: 'event-17',
      couponId: 'coupon-10',
      eventType: 'claim',
      timestamp: new Date('2024-12-18T16:00:00'),
      beneficiary: getBeneficiary(9),
      amount: new BigNumber('45.5'),
      allocationReason: 'API improvements',
    },
    {
      id: 'event-18',
      couponId: 'coupon-11',
      eventType: 'activation',
      timestamp: new Date('2024-12-11T08:45:00'),
      beneficiary: getBeneficiary(0),
      amount: new BigNumber('18.75'),
      allocationReason: 'Network monitoring tools',
    },
    {
      id: 'event-19',
      couponId: 'coupon-12',
      eventType: 'activation',
      timestamp: new Date('2024-12-12T13:20:00'),
      beneficiary: getBeneficiary(1),
      amount: new BigNumber('35.0'),
      allocationReason: 'Security enhancements',
    },
    {
      id: 'event-20',
      couponId: 'coupon-12',
      eventType: 'expiration',
      timestamp: new Date('2024-12-19T00:00:00'),
      beneficiary: getBeneficiary(1),
      amount: new BigNumber('35.0'),
      allocationReason: 'Security enhancements',
    },
    {
      id: 'event-21',
      couponId: 'coupon-13',
      eventType: 'activation',
      timestamp: new Date('2024-12-20T10:00:00'),
      beneficiary: getBeneficiary(2),
      amount: new BigNumber('65.0'),
      allocationReason: 'Smart contract development',
    },
    {
      id: 'event-22',
      couponId: 'coupon-13',
      eventType: 'claim',
      timestamp: new Date('2024-12-28T14:30:00'),
      beneficiary: getBeneficiary(2),
      amount: new BigNumber('65.0'),
      allocationReason: 'Smart contract development',
    },
    {
      id: 'event-23',
      couponId: 'coupon-14',
      eventType: 'activation',
      timestamp: new Date('2025-01-05T09:00:00'),
      beneficiary: getBeneficiary(3),
      amount: new BigNumber('40.0'),
      allocationReason: 'Cross-chain bridge development',
    },
    {
      id: 'event-24',
      couponId: 'coupon-15',
      eventType: 'activation',
      timestamp: new Date('2025-01-10T11:15:00'),
      beneficiary: getBeneficiary(4),
      amount: new BigNumber('28.5'),
      allocationReason: 'SDK improvements',
    },
    {
      id: 'event-25',
      couponId: 'coupon-15',
      eventType: 'withdrawal',
      timestamp: new Date('2025-01-18T16:45:00'),
      beneficiary: getBeneficiary(4),
      amount: new BigNumber('28.5'),
      allocationReason: 'SDK improvements',
      withdrawalReason: 'Requirements changed',
    },
  ];

  return events;
};

export const mockListCouponHistoryEvents = async (
  pageSize: number = 10,
  offset: number = 0
): Promise<{ events: CouponHistoryEvent[]; total: number }> => {
  const allEvents = generateCouponHistoryEvents();

  // Sort by timestamp descending (most recent first)
  const sortedEvents = [...allEvents].sort(
    (a, b) => b.timestamp.getTime() - a.timestamp.getTime()
  );

  const pageEvents = sortedEvents.slice(offset, offset + pageSize);
  const total = sortedEvents.length;

  return { events: pageEvents, total };
};

// Keep the old function for backward compatibility but now it's based on active coupons
export const mockListDevelopmentFundCouponsHistory = async (
  pageSize: number = 10,
  offset: number = 0
): Promise<{ coupons: DevelopmentFundCoupon[]; total: number }> => {
  return mockListActiveDevelopmentFundCoupons(pageSize, offset);
};

export const mockWithdrawDevelopmentFundCoupon = async (
  _couponId: string,
  _reason: string
): Promise<void> => {
  // Mock: In real implementation, this would call walletClient.withdrawDevelopmentFundCoupon
  // For now, we just simulate a successful withdrawal
  await new Promise(resolve => setTimeout(resolve, 500));
};
