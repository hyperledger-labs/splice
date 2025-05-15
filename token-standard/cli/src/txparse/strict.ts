// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { PrettyTransactions } from "../commands/listHoldingTransactions";
import { TokenStandardEvent } from "./types";

export function validateStrict(
  output: PrettyTransactions,
  ignoreNames: string[],
): void {
  const errorEvents: TokenStandardEvent[] = [];
  for (const tx of output.transactions) {
    for (const event of tx.events) {
      if (event.label.type === "Create" || event.label.type === "Archive") {
        const templateId = event.label.templateId;
        if (!ignoreNames.some((ignored) => templateId.endsWith(ignored))) {
          errorEvents.push(event);
        }
      }
    }
  }
  if (errorEvents.length > 0) {
    throw new Error(
      `Found creates / archives that are not under a known choice: ${JSON.stringify(
        errorEvents,
      )}`,
    );
  }
}
