// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

export interface CsvRow {
  party: string;
  amount: string;
}

/**
 * Parse a CSV of party IDs and optional amounts.
 *
 * Supported shapes:
 * - Header `party,amount` or `party,expected_amount` — amount per row.
 * - Header `party` only — uses defaultAmount for every row.
 * - No header — auto-detected by absence of "party" in first line.
 */
export function parsePartyCsv(
  content: string,
  defaultAmount: string,
): CsvRow[] {
  const lines = content
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter((l) => l.length > 0);

  if (lines.length === 0) {
    return [];
  }

  const first = lines[0].toLowerCase();
  const hasHeader = first.includes("party");
  const rows = hasHeader ? lines.slice(1) : lines;

  const headerHasAmount =
    hasHeader &&
    (first.includes("amount") ||
      first.includes("expected") ||
      first.split(",").length >= 2);

  const result: CsvRow[] = [];
  for (const line of rows) {
    if (line.trim().startsWith("#")) {
      continue;
    }
    const parts = line.split(",").map((s) => s.trim());
    const party = parts[0];
    if (!party) {
      continue;
    }

    let amount = parts[1];
    if (!amount || amount === "") {
      if (hasHeader && !headerHasAmount) {
        amount = defaultAmount;
      } else if (!hasHeader && parts.length === 1) {
        amount = defaultAmount;
      }
    }
    if (!amount) {
      continue;
    }

    result.push({ party, amount });
  }
  return result;
}
