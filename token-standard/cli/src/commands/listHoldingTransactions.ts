// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  createLedgerApiClient,
  filtersByParty,
} from "../apis/ledger-api-utils";
import { CommandOptions } from "../cli";
import { TokenStandardTransactionInterfaces } from "../constants";
import { TransactionParser } from "../txparse/parser";
import { renderTransaction, Transaction } from "../txparse/types";
import {
  DefaultApi as LedgerJsonApi,
  JsGetUpdatesResponse,
} from "canton-json-api-v2-openapi";

export async function listHoldingTransactions(
  partyId: string,
  opts: CommandOptions & { afterOffset?: string }
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
              true
            ),
            verbose: false,
          },
          transactionShape: {
            TRANSACTION_SHAPE_LEDGER_EFFECTS: {},
            unrecognizedValue: 0,
          },
        },
      },
      beginExclusive: afterOffset,
      verbose: false,
    });
    const acs = (
      await ledgerClient.postV2StateActiveContracts({
        filter: {
          filtersByParty: filtersByParty(
            partyId,
            TokenStandardTransactionInterfaces,
            false
          ),
        },
        verbose: false,
        activeAtOffset: afterOffset,
      })
    ).map((c) => c.contractEntry.JsActiveContract.createdEvent.contractId);
    console.log(
      JSON.stringify(
        await toPrettyTransactions(
          updates,
          partyId,
          new Set(acs),
          ledgerClient
        ),
        null,
        2
      )
    );
  } catch (err) {
    console.error("Failed to list holding transactions.", err);
  }
}

async function toPrettyTransactions(
  updates: JsGetUpdatesResponse[],
  partyId: string,
  mutatingHoldingsAcs: Set<string>,
  ledgerClient: LedgerJsonApi
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
      })
  );

  return {
    // OffsetCheckpoint can be anywhere... or not at all, maybe
    nextOffset: Math.max(
      latestCheckpointOffset,
      ...transactions.map((tx) => tx.offset)
    ),
    transactions: transactions.filter((tx) => tx.events.length > 0).map(renderTransaction),
  };
}

interface PrettyTransactions {
  transactions: Transaction[];
  nextOffset: number;
}
