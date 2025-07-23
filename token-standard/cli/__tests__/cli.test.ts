// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { createProgram } from "../src/token-standard-cli";
import expectedHoldings from "./expected/holdings.json";
import expectedTransferInstructions from "./expected/transfer-instructions.json";
import expectedTxs from "./expected/txs.json";
import { mockLedgerApiServer } from "./mocks/ledger-api";
import fs from "fs";
import { afterAll, afterEach, beforeAll, expect, test, vi } from "vitest";

const ledgerUrl = "http://localhost:6201";

const server = mockLedgerApiServer(ledgerUrl);
beforeAll(() => server.listen());
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

test("list holdings", async () => {
  const logSpy = vi.spyOn(console, "log");

  const program = createProgram();

  await program.parseAsync([
    "run",
    "cli",
    "list-holdings",
    "alice::normalized",
    "-l",
    "http://localhost:6201",
    "-a",
    "valid_token",
  ]);

  const actualOutput = logSpy.mock.calls[0][0];
  fs.writeFileSync("./__tests__/actual/holdings.json", actualOutput);

  expect(logSpy).toHaveBeenCalledWith(
    JSON.stringify(expectedHoldings, null, 2),
  );
});

test("list transfer instructions", async () => {
  const logSpy = vi.spyOn(console, "log");

  const program = createProgram();

  await program.parseAsync([
    "run",
    "cli",
    "list-transfer-instructions",
    "bob::normalized",
    "-l",
    "http://localhost:6201",
    "-a",
    "valid_token",
  ]);

  const actualOutput = logSpy.mock.calls[0][0];
  fs.writeFileSync(
    "./__tests__/actual/transfer-instructions.json",
    actualOutput,
  );

  expect(logSpy).toHaveBeenCalledWith(
    JSON.stringify(expectedTransferInstructions, null, 2),
  );
});

test("list txs", async () => {
  const logSpy = vi.spyOn(console, "log");

  const program = createProgram();

  await program.parseAsync([
    "run",
    "cli",
    "list-holding-txs",
    "alice::normalized",
    "-l",
    "http://localhost:6201",
    "-a",
    "valid_token",
  ]);

  const actualOutput = logSpy.mock.calls[0][0];
  fs.writeFileSync("./__tests__/actual/txs.json", actualOutput);

  expect(logSpy).toHaveBeenCalledWith(JSON.stringify(expectedTxs, null, 2));
});
