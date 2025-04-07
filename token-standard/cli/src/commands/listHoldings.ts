// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  createLedgerApiClient,
  ensureHoldingViewIsPresent,
  filtersByParty,
} from "../apis/ledger-api-utils";
import { CommandOptions } from "../cli";
import { HoldingInterface } from "../constants";
import { JsGetActiveContractsResponse } from "canton-json-api-v2-openapi";

export async function listHoldings(
  partyId: string,
  opts: CommandOptions
): Promise<void> {
  try {
    const ledgerClient = createLedgerApiClient(opts);
    const ledgerEnd = await ledgerClient.getV2StateLedgerEnd();
    const holdings = await ledgerClient.postV2StateActiveContracts({
      filter: {
        filtersByParty: filtersByParty(partyId, [HoldingInterface], false),
      },
      verbose: false,
      activeAtOffset: ledgerEnd.offset,
    });
    const prettyHoldings = holdings.map(toPrettyHolding);
    console.log(JSON.stringify(prettyHoldings, null, 2));
  } catch (err) {
    console.error("Failed to list holdings", err);
  }
}

// Make them nicer to show by excluding stuff useless to users such as the createdEventBlob
export function toPrettyHolding(holding: JsGetActiveContractsResponse) {
  const createdEvent = holding.contractEntry.JsActiveContract.createdEvent;
  return {
    contractId: createdEvent.contractId,
    payload: ensureHoldingViewIsPresent(createdEvent).viewValue,
  };
}
