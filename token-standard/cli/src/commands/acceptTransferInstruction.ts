// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  createLedgerApiClient,
  submitExerciseCommand,
} from "../apis/ledger-api-utils";
import { TransferInstructionInterface } from "../constants";
import { CommandOptions } from "../token-standard-cli";
import { ExerciseCommand } from "@lfdecentralizedtrust/canton-json-api-v2-openapi";
import {
  createConfiguration,
  DefaultApi as TransferFactoryAPI,
  ServerConfiguration,
} from "@lfdecentralizedtrust/transfer-instruction-openapi";

interface AcceptTransferInstructionCommandOptions {
  // paths to keys
  publicKey: string;
  privateKey: string;
  transferFactoryRegistryUrl: string;
  party: string;
  userId: string;
}

export async function acceptTransferInstruction(
  transferInstructionCid: string,
  opts: CommandOptions & AcceptTransferInstructionCommandOptions,
): Promise<void> {
  try {
    const { privateKey, publicKey, party, userId, transferFactoryRegistryUrl } =
      opts;
    const ledgerClient = createLedgerApiClient(opts);
    const transferRegistryConfig = createConfiguration({
      baseServer: new ServerConfiguration(transferFactoryRegistryUrl, {}),
    });
    const transferRegistryClient = new TransferFactoryAPI(
      transferRegistryConfig,
    );

    const choiceContext =
      await transferRegistryClient.getTransferInstructionAcceptContext(
        transferInstructionCid,
        {},
      );

    const exercise: ExerciseCommand = {
      templateId: TransferInstructionInterface.toString(),
      contractId: transferInstructionCid,
      choice: "TransferInstruction_Accept",
      choiceArgument: {
        extraArgs: {
          context: choiceContext.choiceContextData,
          meta: { values: {} },
        },
      },
    };

    const completion = await submitExerciseCommand(
      ledgerClient,
      exercise,
      choiceContext.disclosedContracts,
      party,
      userId,
      publicKey,
      privateKey,
    );
    const result = { ...completion, status: "success" };

    console.log(JSON.stringify(result, null, 2));
  } catch (e) {
    console.error("Failed to accept transfer instruction:", e);
  }
}
