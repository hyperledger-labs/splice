// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  createLedgerApiClient,
  filtersByParty,
} from "../apis/ledger-api-utils";
import { TokenStandardTransactionInterfaces } from "../constants";
import { CommandOptions } from "../token-standard-cli";
import { TransactionParser } from "../txparse/parser";
import { validateStrict } from "../txparse/strict";
import { renderTransaction, Transaction } from "../txparse/types";
import {
  DefaultApi as LedgerJsonApi,
  JsGetUpdatesResponse,
} from "@lfdecentralizedtrust/canton-json-api-v2-openapi";
import fs from "fs";

export async function listHoldingTransactions(
  partyId: string,
  opts: CommandOptions & {
    afterOffset?: string;
    debugPath?: string;
    strict?: boolean;
    strictIgnore?: string[];
  },
): Promise<void> {
  try {
    const ledgerClient: LedgerJsonApi = createLedgerApiClient(opts);
    const afterOffset =
      Number(opts.afterOffset) ||
      (await ledgerClient.getV2StateLatestPrunedOffsets())
        .participantPrunedUpToInclusive;
    const updates = await ledgerClient.postV2UpdatesFlats({
      updateFormat: {
        includeTransactions: {
          eventFormat: {
            filtersByParty: filtersByParty(
              partyId,
              TokenStandardTransactionInterfaces,
              true,
            ),
            verbose: false,
          },
          transactionShape: "TRANSACTION_SHAPE_LEDGER_EFFECTS",
        },
      },
      beginExclusive: afterOffset,
      verbose: false,
    });
    if (opts.debugPath) {
      fs.writeFileSync(opts.debugPath, JSON.stringify(updates, null, 2));
    }
    const result = await toPrettyTransactions(updates, partyId, ledgerClient);
    if (opts.strict) {
      validateStrict(result, opts.strictIgnore || []);
    }
    console.log(JSON.stringify(result, null, 2));
  } catch (err) {
    console.error("Failed to list holding transactions.", err);
    throw err;
  }
}

async function toPrettyTransactions(
  updates: JsGetUpdatesResponse[],
  partyId: string,
  ledgerClient: LedgerJsonApi,
): Promise<PrettyTransactions> {
  const offsetCheckpoints: number[] = updates
    .filter((update) => update.update.OffsetCheckpoint)
    .map((update) => update.update.OffsetCheckpoint.value.offset);
  const latestCheckpointOffset = Math.max(...offsetCheckpoints);

  const transactions: Transaction[] = await Promise.all(
    updates
      // exclude OffsetCheckpoint, Reassignment, TopologyTransaction
      .filter((update) => !!update.update?.Transaction?.value)
      .map(async (update) => {
        const tx = update.update.Transaction.value;
        const parser = new TransactionParser(tx, ledgerClient, partyId);

        return await parser.parseTransaction();
      }),
  );

  return {
    // OffsetCheckpoint can be anywhere... or not at all, maybe
    nextOffset: Math.max(
      latestCheckpointOffset,
      ...transactions.map((tx) => tx.offset),
    ),
    transactions: transactions
      .filter((tx) => tx.events.length > 0)
      .map(renderTransaction),
  };
}

export interface PrettyTransactions {
  transactions: Transaction[];
  nextOffset: number;
}
