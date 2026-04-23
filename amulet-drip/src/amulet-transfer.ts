// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  Command,
  createConfiguration,
  DeduplicationPeriod2,
  DefaultApi as LedgerJsonApi,
  HttpAuthAuthentication,
  ServerConfiguration,
} from "@lfdecentralizedtrust/canton-json-api-v2-openapi";
import {
  createConfiguration as createTransferConfig,
  DefaultApi as TransferFactoryAPI,
  ServerConfiguration as TransferServerConfig,
} from "@lfdecentralizedtrust/transfer-instruction-openapi";
import dayjs from "dayjs";
import { randomUUID } from "node:crypto";
import { logger } from "./logger.js";
import type { DripConfig } from "./config.js";
import {
  extractHoldings,
  selectBestHolding,
  parseTransferResult,
  totalBalance,
  type Holding,
} from "./holdings.js";

const TRANSFER_FACTORY_TEMPLATE =
  "#splice-api-token-transfer-instruction-v1:Splice.Api.Token.TransferInstructionV1:TransferFactory";
const AMULET_TEMPLATE = "#splice-amulet:Splice.Amulet:Amulet";

export function createLedgerClient(config: DripConfig): LedgerJsonApi {
  return new LedgerJsonApi(
    createConfiguration({
      baseServer: new ServerConfiguration(config.participantLedgerApi, {}),
      authMethods: {
        default: new HttpAuthAuthentication({
          getToken: () => Promise.resolve(config.ledgerAccessToken),
        }),
      },
    }),
  );
}

export function createTransferFactoryClient(
  config: DripConfig,
): TransferFactoryAPI {
  return new TransferFactoryAPI(
    createTransferConfig({
      baseServer: new TransferServerConfig(config.validatorApiUrl, {}),
      authMethods: {
        default: {
          getName: () => "bearer",
          applySecurityAuthentication: (context: any) => {
            context.setHeaderParam(
              "Authorization",
              "Bearer " + config.validatorAccessToken,
            );
          },
        },
      },
    }),
  );
}

/**
 * Query sender's active Amulet holdings from the ledger.
 * Returns parsed Holding[] with contractId and amount.
 */
export async function getSenderHoldings(
  ledgerClient: LedgerJsonApi,
  config: DripConfig,
): Promise<Holding[]> {
  const ledgerEnd = await ledgerClient.getV2StateLedgerEnd();
  const results = await ledgerClient.postV2StateActiveContracts({
    filter: {
      filtersByParty: {
        [config.senderPartyId]: {
          cumulative: [
            {
              identifierFilter: {
                TemplateFilter: {
                  value: {
                    templateId: AMULET_TEMPLATE,
                    includeCreatedEventBlob: true,
                  },
                },
              },
            } as any,
          ],
        },
      },
    },
    verbose: false,
    activeAtOffset: ledgerEnd.offset!,
  });

  const rawContracts = results.map(
    (h: any) =>
      h.contractEntry?.activeContract ??
      h.contractEntry?.JsActiveContract ??
      h.activeContract,
  );
  return extractHoldings(
    rawContracts,
    config.instrumentAdmin,
    config.instrumentId,
  );
}

export interface TransferResult {
  status: "success" | "error";
  updateId?: string;
  receiverAmuletCid?: string | null;
  changeCid?: string | null;
  changeAmount?: number | null;
  error?: string;
}

export async function transferAmulet(
  config: DripConfig,
  ledgerClient: LedgerJsonApi,
  transferFactoryClient: TransferFactoryAPI,
  receiverPartyId: string,
  amount: string,
  metaReason: string,
): Promise<TransferResult> {
  // Query holdings and select best-fit (UTXO-aware)
  const holdings = await getSenderHoldings(ledgerClient, config);
  if (holdings.length === 0) {
    throw new Error(
      `Sender has no Amulet holdings (party: ${config.senderPartyId})`,
    );
  }

  const requestedAmount = parseFloat(amount);
  const bestHolding = selectBestHolding(holdings, requestedAmount);
  logger.debug(
    {
      selectedCid: bestHolding.contractId,
      selectedAmount: bestHolding.amount,
      requestedAmount,
      totalHoldings: holdings.length,
      totalBalance: totalBalance(holdings),
    },
    "Selected holding for transfer",
  );

  const now = dayjs();
  const choiceArgs: any = {
    expectedAdmin: config.instrumentAdmin,
    transfer: {
      sender: config.senderPartyId,
      receiver: receiverPartyId,
      amount,
      instrumentId: {
        admin: config.instrumentAdmin,
        id: config.instrumentId,
      },
      lock: null,
      requestedAt: now.toISOString(),
      executeBefore: now.add(24, "hour").toISOString(),
      inputHoldingCids: [bestHolding.contractId],
      meta: {
        values: {
          "splice.lfdecentralizedtrust.org/reason": metaReason,
        },
      },
    },
    extraArgs: {
      context: { values: {} },
      meta: { values: {} },
    },
  };

  // Get transfer factory from registry
  const transferFactory = await transferFactoryClient.getTransferFactory({
    choiceArguments: choiceArgs,
  });
  if (!transferFactory?.factoryId || !transferFactory?.choiceContext) {
    throw new Error("Failed to get transfer factory from registry");
  }

  choiceArgs.extraArgs.context =
    transferFactory.choiceContext.choiceContextData;

  const exerciseCommand = {
    templateId: TRANSFER_FACTORY_TEMPLATE,
    contractId: transferFactory.factoryId,
    choice: "TransferFactory_Transfer",
    choiceArgument: choiceArgs,
  };

  const command = new Command();
  command.ExerciseCommand = exerciseCommand;

  const deduplicationPeriod = new DeduplicationPeriod2();
  deduplicationPeriod.Empty = {};

  // Submit and wait for full transaction (not just completion)
  // so we can verify the result and extract created contracts
  const txResult = await ledgerClient.postV2CommandsSubmitAndWaitForTransaction(
    {
      commands: {
        commands: [command],
        commandId: `amulet-drip-${randomUUID()}`,
        submissionId: randomUUID(),
        actAs: [config.senderPartyId],
        readAs: [config.senderPartyId],
        userId: config.adminUser,
        disclosedContracts:
          transferFactory.choiceContext.disclosedContracts ?? [],
        deduplicationPeriod,
        synchronizerId: config.synchronizerId,
        workflowId: "amulet-drip",
        packageIdSelectionPreference: [],
      },
    },
  );

  // Verify the transaction produced expected Amulet contracts
  const created = parseTransferResult(
    txResult,
    config.senderPartyId,
    receiverPartyId,
  );

  if (!created.receiverAmuletCid) {
    logger.warn(
      { receiverPartyId, txResult },
      "Transfer completed but no receiver Amulet contract found in transaction",
    );
  }

  return {
    status: "success",
    updateId: txResult?.transaction?.updateId ?? undefined,
    receiverAmuletCid: created.receiverAmuletCid,
    changeCid: created.changeCid,
    changeAmount: created.changeAmount,
  };
}

export async function transferAmuletWithRetry(
  config: DripConfig,
  ledgerClient: LedgerJsonApi,
  transferFactoryClient: TransferFactoryAPI,
  receiverPartyId: string,
  amount: string,
  metaReason: string,
  retryCount: number,
): Promise<TransferResult> {
  let lastError: unknown;
  for (let attempt = 0; attempt <= retryCount; attempt++) {
    try {
      return await transferAmulet(
        config,
        ledgerClient,
        transferFactoryClient,
        receiverPartyId,
        amount,
        metaReason,
      );
    } catch (err) {
      lastError = err;
      if (attempt < retryCount) {
        logger.warn(
          {
            receiverPartyId,
            amount,
            attempt: attempt + 1,
            maxRetries: retryCount,
          },
          "Transfer failed, retrying",
        );
      }
    }
  }
  throw lastError;
}
