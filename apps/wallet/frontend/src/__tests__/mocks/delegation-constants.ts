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

export const dsoPartyId =
  'DSO::1220fb89d62774bd5b3fd8a11c1b22c8c5453e8286c3cf7add515c98d7bca192ef18';

export const charliePartyId =
  'charlie__wallet__user::12201d5aa725ec9491490fd860e86f849358604f6fd387053771cafb90384a94c3e4';

export const davePartyId =
  'dave__wallet__user::12201d5aa725ec9491490fd860e86f849358604f6fd387053771cafb90384a94c3e5';

export const mockMintingDelegations: MintingDelegation[] = [
  {
    beneficiary: bobPartyId,
    delegate: alicePartyId,
    dso: dsoPartyId,
    expiresAt: delegationExpiresAt,
    amuletMergeLimit: '10',
  },
  {
    beneficiary: charliePartyId,
    delegate: alicePartyId,
    dso: dsoPartyId,
    expiresAt: delegationExpiresAt,
    amuletMergeLimit: '5',
  },
];

export const mockMintingDelegationProposals: MintingDelegationProposal[] = [
  {
    delegation: {
      beneficiary: charliePartyId,
      delegate: alicePartyId,
      dso: dsoPartyId,
      expiresAt: delegationExpiresAt,
      amuletMergeLimit: '15',
    },
  },
  {
    delegation: {
      beneficiary: davePartyId,
      delegate: alicePartyId,
      dso: dsoPartyId,
      expiresAt: delegationExpiresAt,
      amuletMergeLimit: '20',
    },
  },
];
