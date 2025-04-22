// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import fs from "fs";
import { afterAll, afterEach, beforeAll, expect, test, vi } from "vitest";
import { createProgram } from "../src/cli";
import expectedHoldings from "./expected/holdings.json";
import expectedTxs from "./expected/txs.json";
import { mockLedgerApiServer } from "./mocks/ledger-api";

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

  const actualOutput = logSpy.mock.calls[0][0]
  fs.writeFileSync("./__tests__/actual/holdings.json", actualOutput)

  expect(logSpy).toHaveBeenCalledWith(
    JSON.stringify(expectedHoldings, null, 2)
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

  const actualOutput = logSpy.mock.calls[0][0]
  fs.writeFileSync("./__tests__/actual/txs.json", actualOutput)

  expect(logSpy).toHaveBeenCalledWith(JSON.stringify(expectedTxs, null, 2));
});
