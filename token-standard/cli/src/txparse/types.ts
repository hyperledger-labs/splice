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
  lockedHoldingsChange: HoldingsChange;
  lockedHoldingsChangeSummary: HoldingsChangeSummary;
  unlockedHoldingsChange: HoldingsChange;
  unlockedHoldingsChangeSummary: HoldingsChangeSummary;
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

export interface HoldingLock {
  holders: string[];
  expiresAt?: string;
  expiresAfter?: string;
  context?: string;
}

export interface HoldingsChange {
  creates: Holding[];
  archives: Holding[];
}

export interface HoldingsChangeSummary {
  numInputs: number;
  inputAmount: string;
  numOutputs: number;
  outputAmount: string;
  amountChange: string;
}
export const EmptyHoldingsChangeSummary: HoldingsChangeSummary = {
  numInputs: 0,
  inputAmount: "0",
  numOutputs: 0,
  outputAmount: "0",
  amountChange: "0",
};

export type Label =
  | TransferOut
  | TransferIn
  | MergeSplit
  | Burn
  | Mint
  | Unlock
  | ExpireDust
  | UnknownAction;
type UnknownAction = RawArchive | RawCreate;
interface BaseLabel {
  type: string;
  meta: any;
}
interface KnownLabel extends BaseLabel {
  mintAmount: string;
  burnAmount: string;
  reason: string | null;
  tokenStandardChoice: TokenStandardChoice | null;
}
export interface TokenStandardChoice {
  name: string;
  choiceArgument: any;
  exerciseResult: any;
}

interface TransferOut extends KnownLabel {
  type: "TransferOut";
  receiverAmounts: Array<{ receiver: string; amount: string }>;
}

interface TransferIn extends KnownLabel {
  type: "TransferIn";
  sender: string;
}

interface MergeSplit extends KnownLabel {
  type: "MergeSplit";
}

// Same as MergeSplit, but is more precise (tx-kind=burn)
interface Burn extends KnownLabel {
  type: "Burn";
}

// Same as MergeSplit, but is more precise (tx-kind=mint)
interface Mint extends KnownLabel {
  type: "Mint";
}

interface Unlock extends KnownLabel {
  type: "Unlock";
}

interface ExpireDust extends KnownLabel {
  type: "ExpireDust";
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
  meta: any;
}
interface RawCreate extends BaseLabel {
  type: "RawCreate";
  parentChoice: string;
  contractId: string;
  offset: number;
  templateId: string;
  payload: Holding;
  packageName: string;
  meta: any;
}
