// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { ExerciseCommand } from "@lfdecentralizedtrust/canton-json-api-v2-openapi";
import dayjs from "dayjs";
import {
  createConfiguration,
  ServerConfiguration,
  DefaultApi as TransferFactoryAPI,
} from "@lfdecentralizedtrust/transfer-instruction-openapi";
import {
  createLedgerApiClient,
  filtersByParty,
  submitExerciseCommand,
} from "../apis/ledger-api-utils";
import { HoldingInterface } from "../constants";
import { CommandOptions } from "../token-standard-cli";

interface TransferCommandOptions {
  sender: string;
  receiver: string;
  amount: string;
  // paths to keys
  publicKey: string;
  privateKey: string;
  instrumentAdmin: string; // TODO (#907): replace with registry call
  instrumentId: string;
  transferFactoryRegistryUrl: string;
  userId: string;
  reason: string;
}

export async function transfer(
  opts: CommandOptions & TransferCommandOptions,
): Promise<void> {
  try {
    const {
      sender,
      receiver,
      amount,
      privateKey,
      publicKey,
      userId,
      instrumentAdmin,
      instrumentId,
      transferFactoryRegistryUrl,
      reason,
    } = opts;
    const ledgerClient = createLedgerApiClient(opts);
    const transferRegistryConfig = createConfiguration({
      baseServer: new ServerConfiguration(transferFactoryRegistryUrl, {}),
    });
    const transferRegistryClient = new TransferFactoryAPI(
      transferRegistryConfig,
    );

    const ledgerEndOffset = await ledgerClient.getV2StateLedgerEnd();
    const senderHoldings = await ledgerClient.postV2StateActiveContracts({
      filter: {
        filtersByParty: filtersByParty(sender, [HoldingInterface], false),
      },
      verbose: false,
      activeAtOffset: ledgerEndOffset.offset!,
    });
    if (senderHoldings.length === 0) {
      throw new Error("Sender has no holdings, so transfer can't be executed.");
    }
    const holdings = senderHoldings.map(
      (h) => h["contractEntry"]!["JsActiveContract"],
    );
    const inputHoldingCids = holdings.map(
      (h) => h["createdEvent"]["contractId"],
    );

    const now = dayjs();
    const choiceArgs: any = {
      expectedAdmin: instrumentAdmin,
      transfer: {
        sender,
        receiver,
        amount,
        instrumentId: { admin: instrumentAdmin, id: instrumentId },
        lock: null,
        requestedAt: now,
        executeBefore: now.add(24, "hour").toISOString(),
        inputHoldingCids,
        meta: {
          values: {
            "splice.lfdecentralizedtrust.org/reason": reason,
          },
        },
      },
      extraArgs: {
        context: { values: {} },
        meta: { values: {} },
      },
    };

    const transferFactory = await transferRegistryClient.getTransferFactory({
      choiceArguments: choiceArgs,
    });
    choiceArgs.extraArgs.context =
      transferFactory.choiceContext.choiceContextData;

    const exercise: ExerciseCommand = {
      templateId:
        "#splice-api-token-transfer-instruction-v1:Splice.Api.Token.TransferInstructionV1:TransferFactory",
      contractId: transferFactory.factoryId,
      choice: "TransferFactory_Transfer",
      choiceArgument: choiceArgs,
    };
    const completion = await submitExerciseCommand(
      ledgerClient,
      exercise,
      transferFactory.choiceContext.disclosedContracts,
      sender,
      userId,
      publicKey,
      privateKey,
    );
    const result = { ...completion, status: "success" };

    console.log(JSON.stringify(result, null, 2));
  } catch (e) {
    console.error("Failed to execute transfer:", e);
  }
}
