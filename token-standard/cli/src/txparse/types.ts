// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
export interface Transaction {
  updateId: string;
  offset: number;
  recordTime: string;
  synchronizerId: string;
  events: TokenStandardEvent[];
}

export interface TokenStandardEvent {
  label: Label;
  holdingsChange: HoldingsChange;
}

// Same definition as HoldingView in Daml
export interface Holding {
  contractId: string;
  owner: string;
  instrumentId: { admin: string; id: string };
  amount: string;
  lock: HoldingLock | null;
  meta: any;
}

interface HoldingLock {
  holders: string[];
  expiresAt?: string;
  expiresAfter?: string;
  context?: string;
}

export interface HoldingsChange {
  creates: Holding[];
  archives: Holding[];
}

export type Label =
  | TransferOut
  | TransferIn
  | Split
  | CombinedBurnMint
  | Burn
  | Mint
  | UnknownAction;
type UnknownAction = RawArchive | RawCreate;
interface BaseLabel {
  type: string;
  meta: any;
}

interface TransferOut extends BaseLabel {
  type: "TransferOut";
  receiverAmounts: Array<{ receiver: string; amount: string }>;
  senderAmount: string;
}

interface TransferIn extends BaseLabel {
  type: "TransferIn";
  sender: string;
  amount: string;
}

interface Split extends BaseLabel {
  type: "Split";
  // amounts: string[]; This can be derived from the creates/archives
}

interface Burn extends BaseLabel {
  type: "Burn";
  reason: string | null;
}

interface Mint extends BaseLabel {
  type: "Mint";
  reason: string | null;
}

interface CombinedBurnMint extends BaseLabel {
  type: "CombinedBurnMint";
  reason: string | null;
}

interface RawArchive extends BaseLabel {
  type: "RawArchive";
  parentChoice: string;
  contractId: string;
  offset: number;
  templateId: string;
  packageName: string;
  actingParties: string[];
  payload: Holding;
  meta: undefined;
}
interface RawCreate extends BaseLabel {
  type: "RawCreate";
  parentChoice: string;
  contractId: string;
  offset: number;
  templateId: string;
  payload: Holding;
  packageName: string;
  meta: undefined;
}
