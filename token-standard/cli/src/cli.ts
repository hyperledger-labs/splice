// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { listHoldingTransactions } from "./commands/listHoldingTransactions";
import { listHoldings } from "./commands/listHoldings";
import { Command } from "commander";

export interface CommandOptions {
  ledgerUrl: string;
  authToken: string;
}

export function createProgram() {
  const program = new Command();

  program
    .version("0.1.0")
    .description("A CLI to interact with the token standard");

  addSharedOptions(
    program
      .command("list-holdings")
      .description("List the holdings of a party")
      .argument("partyId", "The party for which to list the holdings")
  ).action(listHoldings);

  addSharedOptions(
    program
      .command("list-holding-txs")
      .description(
        "List transactions where a party is involved exercising Holding contracts"
      )
      .argument("partyId", "The party for which to list the transactions")
      .option(
        "-o --after-offset <value>",
        "Get transactions after this offset (exclusive)."
      )
  ).action(listHoldingTransactions);

  return program;
}

// Apparently adding them to the base program does not work...
function addSharedOptions(program: Command) {
  return program
    .requiredOption(
      "-l, --ledger-url <value>",
      "The ledger JSON API base URL, e.g. http://localhost:6201"
    )
    .requiredOption(
      "-a, --auth-token <value>",
      "The ledger JSON API auth token"
    );
}
