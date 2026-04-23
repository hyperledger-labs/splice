// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from "vitest";
import {
  extractHoldings,
  selectBestHolding,
  parseTransferResult,
  type Holding,
} from "../src/holdings.js";

describe("extractHoldings", () => {
  const ADMIN = "dso::1220abc";
  const ID = "Amulet";

  function mockContract(contractId: string, amount: string, dso: string): any {
    return {
      createdEvent: {
        contractId,
        createArguments: {
          dso,
          amount: { initialAmount: amount },
        },
      },
    };
  }

  it("extracts holdings with contractId and amount", () => {
    const raw = [
      mockContract("cid-1", "100.0", ADMIN),
      mockContract("cid-2", "50.0", ADMIN),
    ];
    const result = extractHoldings(raw, ADMIN, ID);
    expect(result).toEqual([
      { contractId: "cid-1", amount: 100.0 },
      { contractId: "cid-2", amount: 50.0 },
    ]);
  });

  it("filters out holdings with wrong instrument admin", () => {
    const raw = [
      mockContract("cid-1", "100.0", ADMIN),
      mockContract("cid-2", "50.0", "other::1220xyz"),
    ];
    const result = extractHoldings(raw, ADMIN, ID);
    expect(result).toEqual([{ contractId: "cid-1", amount: 100.0 }]);
  });

  it("skips entries without createdEvent.contractId", () => {
    const raw = [{ createdEvent: {} }, mockContract("cid-1", "100.0", ADMIN)];
    const result = extractHoldings(raw, ADMIN, ID);
    expect(result).toEqual([{ contractId: "cid-1", amount: 100.0 }]);
  });

  it("handles createArgument (singular) shape", () => {
    const raw = [
      {
        createdEvent: {
          contractId: "cid-1",
          createArgument: {
            dso: ADMIN,
            amount: { initialAmount: "75.0" },
          },
        },
      },
    ];
    const result = extractHoldings(raw, ADMIN, ID);
    expect(result).toEqual([{ contractId: "cid-1", amount: 75.0 }]);
  });

  it("returns empty array for empty input", () => {
    expect(extractHoldings([], ADMIN, ID)).toEqual([]);
  });
});

describe("selectBestHolding", () => {
  const holdings: Holding[] = [
    { contractId: "cid-small", amount: 10.0 },
    { contractId: "cid-medium", amount: 50.0 },
    { contractId: "cid-large", amount: 200.0 },
  ];

  it("picks smallest holding that covers the requested amount", () => {
    const result = selectBestHolding(holdings, 30.0);
    expect(result.contractId).toBe("cid-medium");
  });

  it("picks exact match when available", () => {
    const result = selectBestHolding(holdings, 50.0);
    expect(result.contractId).toBe("cid-medium");
  });

  it("falls back to largest when no single holding covers the amount", () => {
    const result = selectBestHolding(holdings, 500.0);
    expect(result.contractId).toBe("cid-large");
  });

  it("picks the only holding when there is one", () => {
    const result = selectBestHolding(
      [{ contractId: "cid-only", amount: 5.0 }],
      100.0,
    );
    expect(result.contractId).toBe("cid-only");
  });

  it("throws when holdings array is empty", () => {
    expect(() => selectBestHolding([], 10.0)).toThrow("No holdings available");
  });
});

describe("parseTransferResult", () => {
  const SENDER = "sender::1220abc";
  const RECEIVER = "receiver::1220def";

  function mockTxResponse(createdEvents: any[]): any {
    return {
      transaction: {
        events: createdEvents.map((e) => ({ CreatedEvent: e })),
      },
    };
  }

  function mockAmuletEvent(
    contractId: string,
    owner: string,
    amount: string,
  ): any {
    return {
      contractId,
      templateId: "#splice-amulet:Splice.Amulet:Amulet",
      createArguments: {
        owner,
        amount: { initialAmount: amount },
      },
    };
  }

  it("identifies receiver amulet and sender change", () => {
    const tx = mockTxResponse([
      mockAmuletEvent("cid-receiver", RECEIVER, "30.0"),
      mockAmuletEvent("cid-change", SENDER, "70.0"),
    ]);
    const result = parseTransferResult(tx, SENDER, RECEIVER);
    expect(result.receiverAmuletCid).toBe("cid-receiver");
    expect(result.changeCid).toBe("cid-change");
    expect(result.changeAmount).toBe(70.0);
  });

  it("handles single created amulet (no change)", () => {
    const tx = mockTxResponse([
      mockAmuletEvent("cid-receiver", RECEIVER, "100.0"),
    ]);
    const result = parseTransferResult(tx, SENDER, RECEIVER);
    expect(result.receiverAmuletCid).toBe("cid-receiver");
    expect(result.changeCid).toBeNull();
    expect(result.changeAmount).toBeNull();
  });

  it("handles createArgument (singular) shape", () => {
    const tx = {
      transaction: {
        events: [
          {
            CreatedEvent: {
              contractId: "cid-receiver",
              templateId: "#splice-amulet:Splice.Amulet:Amulet",
              createArgument: {
                owner: RECEIVER,
                amount: { initialAmount: "50.0" },
              },
            },
          },
        ],
      },
    };
    const result = parseTransferResult(tx, SENDER, RECEIVER);
    expect(result.receiverAmuletCid).toBe("cid-receiver");
  });

  it("returns null fields when no Amulet contracts in tx", () => {
    const tx = mockTxResponse([]);
    const result = parseTransferResult(tx, SENDER, RECEIVER);
    expect(result.receiverAmuletCid).toBeNull();
    expect(result.changeCid).toBeNull();
  });
});
