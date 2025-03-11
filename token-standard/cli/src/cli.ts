// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { listHoldings } from "./commands/listHoldings";
import { Command } from "commander";

export function createProgram() {
  const program = new Command();

  program
    .version("0.1.0")
    .description("A CLI to interact with the token standard");

  program
    .command("list-holdings")
    .description("List the holdings of a party")
    .argument("partyId", "The party for which to list the holdings")
    .requiredOption(
      "-l, --ledger-url <value>",
      "The ledger JSON API base URL, e.g. http://localhost:6201"
    )
    .requiredOption(
      "-a, --auth-token <value>",
      "The ledger JSON API auth token"
    )
    .action(listHoldings);

  return program;
}
