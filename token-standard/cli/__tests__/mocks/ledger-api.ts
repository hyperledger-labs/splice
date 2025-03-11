// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import holdings from "./data/holdings.json";
import { HttpHandler, http, HttpResponse } from "msw";
import { setupServer } from "msw/node";

export const buildLedgerApiMock = (ledgerUrl: string): HttpHandler[] => [
  http.get(`${ledgerUrl}/v2/state/ledger-end`, () => {
    return HttpResponse.json({ offset: "1" });
  }),
  http.post(`${ledgerUrl}/v2/state/active-contracts`, () => {
    return HttpResponse.json(holdings);
  }),
];

export const mockLedgerApiServer = (ledgerUrl: string) =>
  setupServer(...buildLedgerApiMock(ledgerUrl));
