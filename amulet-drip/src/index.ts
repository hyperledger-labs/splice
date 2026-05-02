// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Command } from "commander";
import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { resolve } from "node:path";
import dotenv from "dotenv";
import { loadDripConfig } from "./config.js";
import { parsePartyCsv } from "./csv-parser.js";
import {
  createLedgerClient,
  createTransferFactoryClient,
  getSenderHoldings,
  transferAmuletWithRetry,
} from "./amulet-transfer.js";
import { totalBalance } from "./holdings.js";
import { logger } from "./logger.js";

dotenv.config();

interface DripResult {
  party: string;
  amount: string;
  status: "success" | "error";
  updateId?: string;
  receiverAmuletCid?: string | null;
  changeCid?: string | null;
  changeAmount?: number | null;
  error?: string;
}

interface OutputDocument {
  generatedAt: string;
  senderParty: string;
  csvFile: string;
  totalRequested: number;
  transfers: DripResult[];
}

/**
 * Rich error formatting.
 * Extracts useful details from Canton/HTTP errors beyond just err.message.
 */
function formatError(err: unknown): string {
  if (err == null) {
    return "Unknown error";
  }
  if (typeof err === "string") {
    return err;
  }
  if (typeof err === "object" && err !== null) {
    const e = err as any;
    // HTTP response errors (from OpenAPI client)
    if (e.code !== undefined && e.body !== undefined) {
      const body = typeof e.body === "string" ? e.body : JSON.stringify(e.body);
      return `HTTP ${e.code}: ${body}`;
    }
    // Canton error shape: { code, cause, context }
    if (e.code !== undefined && e.cause !== undefined) {
      const sub = e.context?.unknownSubmitter;
      return sub
        ? `${e.code}: ${e.cause} (unknownSubmitter: ${sub})`
        : `${e.code}: ${e.cause}`;
    }
    if (e instanceof Error && e.message) {
      return e.message;
    }
    try {
      return JSON.stringify(e);
    } catch {
      return String(e);
    }
  }
  return String(err);
}

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}

/**
 * Write the output document to file (incremental — called after each transfer).
 */
function writeOutput(outputPath: string, doc: OutputDocument): void {
  writeFileSync(outputPath, JSON.stringify(doc, null, 2));
}

async function drip(csvPath: string, opts: { output?: string }): Promise<void> {
  const config = loadDripConfig();
  const ledgerClient = createLedgerClient(config);
  const transferFactoryClient = createTransferFactoryClient(config);

  const content = readFileSync(csvPath, "utf8");
  const entries = parsePartyCsv(content, config.dripAmount);

  if (entries.length === 0) {
    logger.warn({ csvPath }, "No rows to process");
    return;
  }

  // --- Pre-flight validation ---
  const invalidEntries = entries.filter(
    (e) => !e.party || !e.amount || isNaN(parseFloat(e.amount)),
  );
  if (invalidEntries.length > 0) {
    logger.error(
      { invalidEntries },
      "CSV contains invalid entries (missing party or non-numeric amount)",
    );
    process.exitCode = 1;
    return;
  }

  const totalRequested = entries.reduce(
    (sum, e) => sum + parseFloat(e.amount),
    0,
  );

  logger.info(
    {
      count: entries.length,
      totalRequested,
      csvPath,
    },
    "Starting Amulet drip from CSV",
  );

  // --- Balance pre-check ---
  try {
    const holdings = await getSenderHoldings(ledgerClient, config);
    const balance = totalBalance(holdings);
    logger.info(
      {
        holdingCount: holdings.length,
        senderBalance: balance,
        totalRequested,
      },
      "Sender balance check",
    );
    if (balance < totalRequested) {
      logger.warn(
        {
          senderBalance: balance,
          totalRequested,
          deficit: totalRequested - balance,
        },
        "Sender balance may be insufficient (excluding fees). Proceeding anyway.",
      );
    }
  } catch (err) {
    logger.warn(
      { err },
      "Could not check sender balance. Proceeding without pre-check.",
    );
  }

  // --- Prepare output document ---
  const outputDoc: OutputDocument = {
    generatedAt: new Date().toISOString(),
    senderParty: config.senderPartyId,
    csvFile: csvPath,
    totalRequested,
    transfers: [],
  };

  // --- Batch transfer loop with incremental output ---
  for (let i = 0; i < entries.length; i++) {
    const { party, amount } = entries[i];

    if (i > 0) {
      await sleep(config.delayBetweenRowsMs);
    }

    let result: DripResult;
    try {
      logger.info(
        { party, amount, index: i + 1, total: entries.length },
        "Transferring Amulet",
      );
      const res = await transferAmuletWithRetry(
        config,
        ledgerClient,
        transferFactoryClient,
        party,
        amount,
        config.metaReason,
        config.retryCount,
      );
      result = {
        party,
        amount,
        status: "success",
        updateId: res.updateId,
        receiverAmuletCid: res.receiverAmuletCid,
        changeCid: res.changeCid,
        changeAmount: res.changeAmount,
      };
      logger.info(
        {
          party,
          amount,
          index: i + 1,
          total: entries.length,
          receiverAmuletCid: res.receiverAmuletCid,
          changeCid: res.changeCid,
        },
        "Transfer succeeded",
      );
    } catch (err) {
      logger.error({ err, party, amount }, "Transfer failed");
      result = {
        party,
        amount,
        status: "error",
        error: formatError(err),
      };
    }

    outputDoc.transfers.push(result);

    // Incremental output: write after each transfer so partial results survive crashes
    if (opts.output) {
      writeOutput(opts.output, outputDoc);
    }
  }

  // --- Summary ---
  const succeeded = outputDoc.transfers.filter(
    (r) => r.status === "success",
  ).length;
  const failed = outputDoc.transfers.filter((r) => r.status === "error").length;
  logger.info(
    { total: outputDoc.transfers.length, succeeded, failed },
    "Drip complete",
  );

  if (!opts.output) {
    console.log(JSON.stringify(outputDoc, null, 2));
  } else {
    logger.info({ path: opts.output }, "Results written to file");
  }

  if (failed > 0) {
    process.exitCode = 1;
  }
}

const program = new Command();
program
  .name("amulet-drip")
  .version("1.0.0")
  .description("Batch-distribute Amulet (CC) tokens to multiple recipients");

program
  .command("drip")
  .description("Transfer Amulet to all parties listed in a CSV file")
  .argument("<csv-file>", "Path to CSV file (party[,amount] rows)")
  .option(
    "-o, --output <path>",
    "Write results to JSON file (updated after each transfer for crash safety)",
  )
  .action(async (csvFile: string, opts: { output?: string }) => {
    const csvPath = resolve(csvFile);
    if (!existsSync(csvPath)) {
      logger.error({ csvPath }, "CSV file not found");
      process.exitCode = 1;
      return;
    }
    await drip(csvPath, opts);
  });

program.parse();
