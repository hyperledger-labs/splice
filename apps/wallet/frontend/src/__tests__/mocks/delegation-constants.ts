// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  MintingDelegation,
  MintingDelegationProposal,
} from '@daml.js/splice-wallet/lib/Splice/Wallet/MintingDelegation/module';

import { alicePartyId, bobPartyId } from './constants';

// All delegations expire on the same date in 2050
export const delegationExpiresAt = '2050-01-01T00:00:00.000000Z';

// Formatted version as displayed by DateDisplay component
export const delegationExpiresAtFormatted = '2050-01-01 00:00';

// Onboarded status for each delegation (bob is onboarded, charlie is not, eve is onboarded)
export const mockDelegationOnboardedStatus = [true, false, true];

// Onboarded status for each proposal (charlie is onboarded, dave is not, eve is onboarded)
export const mockProposalOnboardedStatus = [true, false, true];

// Three different expiration dates for testing sort order
export const expiresAtCurrent = '2050-01-01T00:00:00.000000Z';
export const expiresAt3Months = '2050-04-01T00:00:00.000000Z';
export const expiresAt6Months = '2050-07-01T00:00:00.000000Z';

export const dsoPartyId =
  'DSO::1220fb89d62774bd5b3fd8a11c1b22c8c5453e8286c3cf7add515c98d7bca192ef18';

export const charliePartyId =
  'charlie__wallet__user::12201d5aa725ec9491490fd860e86f849358604f6fd387053771cafb90384a94c3e4';

export const davePartyId =
  'dave__wallet__user::12201d5aa725ec9491490fd860e86f849358604f6fd387053771cafb90384a94c3e5';

export const evePartyId =
  'eve__wallet__user::12201d5aa725ec9491490fd860e86f849358604f6fd387053771cafb90384a94c3e6';

// Mock data is intentionally NOT sorted by expiration date.
// Order: 3 months, 6 months, current - to verify UI sorts them correctly.
export const mockMintingDelegations: MintingDelegation[] = [
  {
    beneficiary: bobPartyId,
    delegate: alicePartyId,
    dso: dsoPartyId,
    expiresAt: expiresAt3Months,
    amuletMergeLimit: '10',
  },
  {
    beneficiary: charliePartyId,
    delegate: alicePartyId,
    dso: dsoPartyId,
    expiresAt: expiresAt6Months,
    amuletMergeLimit: '5',
  },
  {
    beneficiary: evePartyId,
    delegate: alicePartyId,
    dso: dsoPartyId,
    expiresAt: expiresAtCurrent,
    amuletMergeLimit: '15',
  },
];

// Expected order after sorting by expiration (earliest first): eve (current), bob (3mo), charlie (6mo)
export const mockMintingDelegationsSorted: MintingDelegation[] = [
  mockMintingDelegations[2], // eve - current
  mockMintingDelegations[0], // bob - 3 months
  mockMintingDelegations[1], // charlie - 6 months
];

// Onboarded status in sorted order (eve: true, bob: true, charlie: false)
export const mockDelegationOnboardedStatusSorted = [true, true, false];

// Mock data is intentionally NOT sorted by expiration date.
// Order: 3 months, 6 months, current - to verify UI sorts them correctly.
export const mockMintingDelegationProposals: MintingDelegationProposal[] = [
  {
    delegation: {
      beneficiary: charliePartyId,
      delegate: alicePartyId,
      dso: dsoPartyId,
      expiresAt: expiresAt3Months,
      amuletMergeLimit: '15',
    },
  },
  {
    delegation: {
      beneficiary: davePartyId,
      delegate: alicePartyId,
      dso: dsoPartyId,
      expiresAt: expiresAt6Months,
      amuletMergeLimit: '20',
    },
  },
  {
    delegation: {
      beneficiary: evePartyId,
      delegate: alicePartyId,
      dso: dsoPartyId,
      expiresAt: expiresAtCurrent,
      amuletMergeLimit: '25',
    },
  },
];

// Expected order after sorting by expiration (earliest first): eve (current), charlie (3mo), dave (6mo)
export const mockMintingDelegationProposalsSorted: MintingDelegationProposal[] = [
  mockMintingDelegationProposals[2], // eve - current
  mockMintingDelegationProposals[0], // charlie - 3 months
  mockMintingDelegationProposals[1], // dave - 6 months
];

// Onboarded status in sorted order (eve: true, charlie: true, dave: false)
export const mockProposalOnboardedStatusSorted = [true, true, false];
