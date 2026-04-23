// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

export interface Holding {
  contractId: string;
  amount: number;
}

export interface TransferCreatedContracts {
  receiverAmuletCid: string | null;
  changeCid: string | null;
  changeAmount: number | null;
}

/**
 * Extract holdings from raw active contract responses.
 * Handles multiple response shapes from the Canton Ledger API:
 * - `createArguments` (verbose mode from submit-and-wait-for-transaction)
 * - `createArgument` (singular, some API versions)
 * Filters by instrument admin (DSO party).
 */
export function extractHoldings(
  rawContracts: any[],
  instrumentAdmin: string,
  _instrumentId: string,
): Holding[] {
  const holdings: Holding[] = [];
  for (const h of rawContracts) {
    const contractId = h?.createdEvent?.contractId;
    if (!contractId) {
      continue;
    }
    const createArg =
      h.createdEvent.createArguments ?? h.createdEvent.createArgument;
    if (!createArg) {
      continue;
    }
    const dso = createArg.dso ?? createArg.instrumentId?.admin;
    if (dso && dso !== instrumentAdmin) {
      continue;
    }
    const amountStr =
      createArg.amount?.initialAmount ?? createArg.amount ?? "0";
    const amount = parseFloat(amountStr);
    if (isNaN(amount)) {
      continue;
    }
    holdings.push({ contractId, amount });
  }
  return holdings;
}

/**
 * Select the best holding for a transfer.
 * Strategy (from 03-distribute-amulet.sh):
 * 1. Pick the smallest holding whose amount >= requestedAmount (minimizes change).
 * 2. Fall back to the largest holding if none is large enough.
 */
export function selectBestHolding(
  holdings: Holding[],
  requestedAmount: number,
): Holding {
  if (holdings.length === 0) {
    throw new Error("No holdings available");
  }
  const sorted = [...holdings].sort((a, b) => a.amount - b.amount);
  const bestFit = sorted.find((h) => h.amount >= requestedAmount);
  if (bestFit) {
    return bestFit;
  }
  return sorted[sorted.length - 1];
}

/**
 * Parse a submit-and-wait-for-transaction response to extract created Amulet contracts.
 * Identifies which created Amulet belongs to the receiver and which is the sender's change.
 * Ported from 03-distribute-amulet.sh lines 452-498.
 */
export function parseTransferResult(
  txResponse: any,
  senderPartyId: string,
  receiverPartyId: string,
): TransferCreatedContracts {
  const events = txResponse?.transaction?.events ?? [];
  const amuletEvents = events
    .map((e: any) => e.CreatedEvent ?? e.createdEvent)
    .filter((e: any) => {
      if (!e?.templateId) {
        return false;
      }
      const tid =
        typeof e.templateId === "string"
          ? e.templateId
          : JSON.stringify(e.templateId);
      return tid.includes("Splice") && tid.includes("Amulet:Amulet");
    });

  if (amuletEvents.length === 0) {
    return { receiverAmuletCid: null, changeCid: null, changeAmount: null };
  }

  const getOwner = (e: any): string =>
    e.createArguments?.owner ?? e.createArgument?.owner ?? "";

  const getAmount = (e: any): number => {
    const raw =
      e.createArguments?.amount?.initialAmount ??
      e.createArgument?.amount?.initialAmount ??
      "0";
    return parseFloat(raw);
  };

  if (amuletEvents.length === 1) {
    return {
      receiverAmuletCid: amuletEvents[0].contractId,
      changeCid: null,
      changeAmount: null,
    };
  }

  // Multiple Amulets: match receiver by owner
  let receiverEvent = amuletEvents.find(
    (e: any) => getOwner(e) === receiverPartyId,
  );
  if (!receiverEvent) {
    // Fallback: pick the one NOT owned by sender
    receiverEvent = amuletEvents.find(
      (e: any) => getOwner(e) !== senderPartyId,
    );
  }
  const receiverCid = receiverEvent?.contractId ?? null;

  // Change is the other Amulet (owned by sender)
  const changeEvent = amuletEvents.find(
    (e: any) => e.contractId !== receiverCid,
  );

  return {
    receiverAmuletCid: receiverCid,
    changeCid: changeEvent?.contractId ?? null,
    changeAmount: changeEvent ? getAmount(changeEvent) : null,
  };
}

/**
 * Sum total balance across all holdings.
 */
export function totalBalance(holdings: Holding[]): number {
  return holdings.reduce((sum, h) => sum + h.amount, 0);
}
