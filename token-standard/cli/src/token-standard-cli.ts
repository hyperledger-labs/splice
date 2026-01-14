// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { listHoldingTransactions } from "./commands/listHoldingTransactions";
import { transfer } from "./commands/transfer";
import { Command } from "commander";
import { HoldingInterface, TransferInstructionInterface } from "./constants";
import { listContractsByInterface } from "./commands/listContractsByInterface";
import { acceptTransferInstruction } from "./commands/acceptTransferInstruction";

export interface CommandOptions {
  ledgerUrl: string;
  authToken: string;
}

export function createProgram(): Command {
  const program = new Command();

  program
    .version("0.1.0")
    .description("A CLI to interact with the token standard");

  addSharedOptions(
    program
      .command("list-holdings")
      .description("List the holdings of a party")
      .argument("partyId", "The party for which to list the holdings"),
  ).action((partyId, opts) =>
    listContractsByInterface(HoldingInterface, partyId, opts),
  );

  addSharedOptions(
    program
      .command("list-holding-txs")
      .description(
        "List transactions where a party is involved exercising Holding contracts",
      )
      .argument("partyId", "The party for which to list the transactions")
      .option(
        "-o --after-offset <value>",
        "Get transactions after this offset (exclusive).",
      )
      .option(
        "-d --debug-path <value>",
        "Writes the original server response to this path for debugging purposes.",
      )
      .option(
        "-s --strict",
        "Fail if any creates/archives without a known parent would be returned.",
        false,
      )
      .option(
        "--strict-ignore <values...>",
        "In strict mode only: ignore raw creates / exercises of the given entity names. Space-separated.",
      ),
  ).action(listHoldingTransactions);

  addSharedOptions(
    program
      .command("transfer")
      .description("Send a transfer of holdings")
      .requiredOption("-s, --sender <value>", "The sender party of holdings")
      .requiredOption(
        "-r --receiver <value>",
        "The receiver party of the holdings",
      )
      .requiredOption("--amount <value>", "The amount to be transferred")
      // TODO (#907): remove this option
      .requiredOption(
        "-e --instrument-admin <value>",
        `The expected admin of the instrument.`,
      )
      .requiredOption(
        "-d --instrument-id <value>",
        `The instrument id of the holding, e.g. "Amulet"`,
      )
      .requiredOption("--public-key <value>", "Path to the public key file")
      .requiredOption("--private-key <value>", "Path to the private key file")
      .requiredOption(
        "-R --transfer-factory-registry-url <value>",
        "The URL to a transfer registry.",
      )
      .requiredOption(
        "-u, --user-id <value>",
        "The user id, must match the user in the token",
      )
      .option("--reason <value>", "The reason for the transfer")
      .action(transfer),
  );

  addSharedOptions(
    program
      .command("list-transfer-instructions")
      .description(
        "List all transfer instructions where the provided party is a stakeholder of",
      )
      .argument(
        "partyId",
        "The party for which to list the transfer instructions",
      )
      .action((partyId, opts) =>
        listContractsByInterface(TransferInstructionInterface, partyId, opts),
      ),
  );

  addSharedOptions(
    program
      .command("accept-transfer-instruction")
      .description(
        "Execute the choice TransferInstruction_Accept on the provided transfer instruction",
      )
      .argument(
        "transferInstructionCid",
        "The contract ID of the transfer instruction to accept",
      )
      .requiredOption(
        "-p, --party <value>",
        "The party as which to accept the transfer instruction. Must be usable by the auth token's user.",
      )
      .requiredOption(
        "-u, --user-id <value>",
        "The user id, must match the user in the token",
      )
      .requiredOption("--public-key <value>", "Path to the public key file")
      .requiredOption("--private-key <value>", "Path to the private key file")
      .requiredOption(
        "-R --transfer-factory-registry-url <value>",
        "The URL to a transfer registry.",
      )
      .action((transferInstructionCid, opts) =>
        acceptTransferInstruction(transferInstructionCid, opts),
      ),
  );

  return program;
}

// Apparently adding them to the base program does not work...
function addSharedOptions(program: Command) {
  return program
    .requiredOption(
      "-l, --ledger-url <value>",
      "The ledger JSON API base URL, e.g. http://localhost:6201",
    )
    .requiredOption(
      "-a, --auth-token <value>",
      "The ledger JSON API auth token",
    );
}
