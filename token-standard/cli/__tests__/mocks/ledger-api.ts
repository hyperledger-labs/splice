// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import eventsByContractIdResponses0 from "./data/eventsByContractIdResponse-0.json";
import eventsByContractIdResponses1 from "./data/eventsByContractIdResponse-1.json";
import holdings from "./data/holdings.json";
import txs from "./data/txs.json";
import { HttpHandler, http, HttpResponse } from "msw";
import { setupServer } from "msw/node";

export const buildLedgerApiMock = (ledgerUrl: string): HttpHandler[] => [
  http.get(`${ledgerUrl}/v2/state/ledger-end`, () => {
    return HttpResponse.json({ offset: "1" });
  }),
  http.post(`${ledgerUrl}/v2/state/active-contracts`, () => {
    return HttpResponse.json(holdings);
  }),
  http.get(`${ledgerUrl}/v2/state/latest-pruned-offsets`, () => {
    return HttpResponse.json({
      participantPrunedUpToInclusive: 0,
      allDivulgedContractsPrunedUpToInclusive: 0,
    });
  }),
  http.post(`${ledgerUrl}/v2/updates/flats`, () => {
    return HttpResponse.json(txs);
  }),
  http.post(`${ledgerUrl}/v2/events/events-by-contract-id`, async (req) => {
    const payload: any = await req.request.json();
    const mocks = [eventsByContractIdResponses0, eventsByContractIdResponses1];
    const response = mocks.find(
      (mock) => mock.created.createdEvent.contractId === payload.contractId
    );
    if (!response) {
      throw new Error(
        `Unexpected contract id: ${
          payload.contractId
        }, expected one of ${mocks.map(
          (mock) => mock.created.createdEvent.contractId
        )}`
      );
    } else {
      return HttpResponse.json(response);
    }
  }),
];

export const mockLedgerApiServer = (ledgerUrl: string) =>
  setupServer(...buildLedgerApiMock(ledgerUrl));
