// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { createProgram } from "../src/cli";
import expectedHoldings from "./expected/holdings.json";
import expectedTxs from "./expected/txs.json";
import { mockLedgerApiServer } from "./mocks/ledger-api";
import { beforeAll, afterEach, afterAll, test, expect, vi } from "vitest";

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
    "party::normalized",
    "-l",
    "http://localhost:6201",
    "-a",
    "valid_token",
  ]);

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
    "party::normalized",
    "-l",
    "http://localhost:6201",
    "-a",
    "valid_token",
  ]);

  expect(logSpy).toHaveBeenCalledWith(JSON.stringify(expectedTxs, null, 2));
});
