// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from "vitest";
import { parsePartyCsv } from "../src/csv-parser.js";

describe("parsePartyCsv", () => {
  it("parses party,amount CSV with header", () => {
    const csv = "party,amount\nalice::1220abc,10.0\nbob::1220def,20.0";
    const result = parsePartyCsv(csv, "1.0");
    expect(result).toEqual([
      { party: "alice::1220abc", amount: "10.0" },
      { party: "bob::1220def", amount: "20.0" },
    ]);
  });

  it("parses party-only CSV using default amount", () => {
    const csv = "party\nalice::1220abc\nbob::1220def";
    const result = parsePartyCsv(csv, "5.0");
    expect(result).toEqual([
      { party: "alice::1220abc", amount: "5.0" },
      { party: "bob::1220def", amount: "5.0" },
    ]);
  });

  it("parses headerless CSV with amounts", () => {
    const csv = "alice::1220abc,10.0\nbob::1220def,20.0";
    const result = parsePartyCsv(csv, "1.0");
    expect(result).toEqual([
      { party: "alice::1220abc", amount: "10.0" },
      { party: "bob::1220def", amount: "20.0" },
    ]);
  });

  it("parses headerless party-only CSV using default amount", () => {
    const csv = "alice::1220abc\nbob::1220def";
    const result = parsePartyCsv(csv, "5.0");
    expect(result).toEqual([
      { party: "alice::1220abc", amount: "5.0" },
      { party: "bob::1220def", amount: "5.0" },
    ]);
  });

  it("skips comment lines", () => {
    const csv = "party,amount\n# this is a comment\nalice::1220abc,10.0";
    const result = parsePartyCsv(csv, "1.0");
    expect(result).toEqual([{ party: "alice::1220abc", amount: "10.0" }]);
  });

  it("skips empty lines and trims whitespace", () => {
    const csv = "party,amount\n\n  alice::1220abc , 10.0  \n\n";
    const result = parsePartyCsv(csv, "1.0");
    expect(result).toEqual([{ party: "alice::1220abc", amount: "10.0" }]);
  });

  it("returns empty array for empty input", () => {
    expect(parsePartyCsv("", "1.0")).toEqual([]);
  });

  it("handles expected_amount header variant", () => {
    const csv = "party,expected_amount\nalice::1220abc,10.0";
    const result = parsePartyCsv(csv, "1.0");
    expect(result).toEqual([{ party: "alice::1220abc", amount: "10.0" }]);
  });
});
