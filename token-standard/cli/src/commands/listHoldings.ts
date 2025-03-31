// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { LedgerClient } from "../apis/ledger-client";
import { CommandOptions } from "../cli";
import { HoldingInterface } from "../constants";

export async function listHoldings(
  partyId: string,
  opts: CommandOptions
): Promise<void> {
  try {
    const ledgerClient = new LedgerClient(opts.ledgerUrl, opts.authToken);
    const ledgerEnd = await ledgerClient.getLedgerEnd();
    const holdings: any[] = await ledgerClient.getActiveContractsOfParty(
      partyId,
      ledgerEnd.offset,
      [HoldingInterface]
    );
    const prettyHoldings = holdings.map(toPrettyHolding);
    console.log(JSON.stringify(prettyHoldings, null, 2));
  } catch (err) {
    console.error("Failed to list holdings", err);
  }
}

// Make them nicer to show by excluding stuff useless to users such as the createdEventBlob
export function toPrettyHolding(holding: any) {
  const createdEvent = holding.contractEntry.JsActiveContract.createdEvent;
  return {
    contractId: createdEvent.contractId,
    payload: createdEvent.interfaceViews[0].viewValue,
  };
}
