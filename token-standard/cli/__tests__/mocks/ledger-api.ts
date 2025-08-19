// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import eventsByContractIdResponses from "./data/eventsByContractIdResponses.json";
import holdings from "./data/holdings.json";
import transferInstructions from "./data/transfer-instructions.json";
import txs from "./data/txs.json";
import { HttpHandler, http, HttpResponse } from "msw";
import { setupServer, SetupServerApi } from "msw/node";
import {
  HoldingInterface,
  TransferInstructionInterface,
} from "../../src/constants";

export const buildLedgerApiMock = (ledgerUrl: string): HttpHandler[] => [
  http.get(`${ledgerUrl}/v2/state/ledger-end`, () => {
    return HttpResponse.json({ offset: "1" });
  }),
  http.post(`${ledgerUrl}/v2/state/active-contracts`, async (req) => {
    const requestBody = await req.request.text();
    if (requestBody.includes(HoldingInterface.toString())) {
      return HttpResponse.json(holdings);
    } else if (requestBody.includes(TransferInstructionInterface.toString())) {
      return HttpResponse.json(transferInstructions);
    } else {
      throw new Error(`No test data for interface requested in ${requestBody}`);
    }
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
    const mocks = eventsByContractIdResponses;
    const response = mocks.find(
      (mock) => mock.created.createdEvent.contractId === payload.contractId,
    );
    if (!response) {
      return HttpResponse.json(
        {
          code: 404,
          body: { code: "CONTRACT_EVENTS_NOT_FOUND" },
        },
        { status: 404 },
      );
    } else {
      return HttpResponse.json(response);
    }
  }),
];

export const mockLedgerApiServer: (ledgerUrl: string) => SetupServerApi = (
  ledgerUrl,
) => setupServer(...buildLedgerApiMock(ledgerUrl));
