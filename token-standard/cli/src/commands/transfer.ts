// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as crypto from "crypto";
import dayjs from "dayjs";
import { readFileSync } from "node:fs";
import {
  createConfiguration, DefaultApi as TransferFactoryAPI, ServerConfiguration
} from "transfer-instruction-openapi";
import { DisclosedContract, LedgerClient } from "../apis/ledger-client";
import { CommandOptions } from "../cli";
import { HoldingInterface } from "../constants";

interface TransferCommandOptions {
  sender: string;
  receiver: string;
  amount: string;
  // paths to keys
  publicKey: string;
  privateKey: string;
  instrumentAdmin: string; // TODO (#18611): replace with registry call
  instrumentId: string;
  transferFactoryRegistryUrl: string;
}

export async function transfer(
  opts: CommandOptions & TransferCommandOptions
): Promise<void> {
  const {
    sender,
    receiver,
    amount,
    privateKey,
    publicKey,
    instrumentAdmin,
    instrumentId,
    authToken,
    ledgerUrl,
    transferFactoryRegistryUrl,
  } = opts;
  const ledgerClient = new LedgerClient(ledgerUrl, authToken);
  const transferRegistryConfig = createConfiguration({
    baseServer: new ServerConfiguration(transferFactoryRegistryUrl, {}),
  });
  const transferRegistryClient = new TransferFactoryAPI(transferRegistryConfig);

  const ledgerEndOffset = await ledgerClient.getLedgerEnd();
  const senderHoldings = await ledgerClient.getActiveContractsOfParty(
    sender,
    ledgerEndOffset.offset,
    [HoldingInterface]
  );
  if (senderHoldings.length === 0) {
    throw new Error("Sender has no holdings, so transfer can't be executed.");
  }
  const holdings = senderHoldings.map(
    (h) => h["contractEntry"]["JsActiveContract"]
  );
  const holdingCids = holdings.map((h) => h["createdEvent"]["contractId"]);

  const now = dayjs()
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
      holdingCids,
      meta: { values: [] },
    },
    extraArgs: {
      context: { values: [] },
      meta: { values: [] },
    },
  };

  const transferFactory = await transferRegistryClient.getTransferFactory({
    choiceArguments: choiceArgs,
  });
  choiceArgs.extraArgs.context =
    transferFactory.choiceContext.choiceContextData;

  const commands = [
    {
      ExerciseCommand: {
        templateId:
          "#splice-api-token-transfer-instruction-v1:Splice.Api.Token.TransferInstructionV1:TransferFactory",
        contractId: transferFactory.factoryId,
        choice: "TransferFactory_Transfer",
        choiceArgument: choiceArgs,
      },
    },
  ];
  const disclosedContracts = holdings
    .map((h) => {
      return {
        contractId: h["createdEvent"]["contractId"],
        createdEventBlob: h["createdEvent"]["createdEventBlob"],
        synchronizerId: h["synchronizerId"],
        templateId: h["createdEvent"]["templateId"],
      };
    })
    .concat(
      transferFactory.choiceContext.disclosedContracts
    );

  const synchronizerId =
    getSynchronizerIdFromDisclosedContracts(disclosedContracts);

  const prepared = await ledgerClient.prepareSend(
    sender,
    commands,
    synchronizerId,
    disclosedContracts
  );

  const signed = signTransaction(
    publicKey,
    privateKey,
    prepared.preparedTransactionHash
  );
  const partySignatures = {
    signatures: [
      {
        party: sender,
        signatures: [
          {
            format: { SIGNATURE_FORMAT_RAW: {} },
            signature: signed.signedHash,
            signedBy: signed.signedBy,
            signingAlgorithmSpec: { SIGNING_ALGORITHM_SPEC_ED25519: {} },
          },
        ],
      },
    ],
  };

  const result = await ledgerClient.executeInteractiveSubmission(
    prepared,
    partySignatures
  );

  // TODO (#18610): this is currently '{}'. It should include record_time and update_id, which require usage of completions API
  console.log(JSON.stringify(result, null, 2));
}

// The synchronizer id is mandatory, so we derive it from the disclosed contracts,
// expecting that they'll all be in the same synchronizer
function getSynchronizerIdFromDisclosedContracts(
  disclosedContracts: DisclosedContract[]
): string {
  const synchronizerId = disclosedContracts[0].synchronizerId;
  const differentSynchronizerId = disclosedContracts.find(
    (dc) => dc.synchronizerId !== synchronizerId
  );
  if (differentSynchronizerId) {
    throw new Error(
      `Contract is in a different domain so can't submit to the correct synchronizer: ${JSON.stringify(
        differentSynchronizerId
      )}`
    );
  }
  return synchronizerId;
}

interface SignTransactionResult {
  signedBy: string;
  // base64 encoded
  signedHash: string;
}
function signTransaction(
  publicKeyPath: string,
  privateKeyPath: string,
  preparedTransactionHash: string
): SignTransactionResult {
  const publicKey = readFileSync(publicKeyPath);
  const nodePublicKey = crypto.createPublicKey({
    key: publicKey,
    format: "der",
    type: "spki", // pycryptodome exports public keys as SPKI
  });

  const privateKey = readFileSync(privateKeyPath);
  const nodePrivateKey = crypto.createPrivateKey({
    key: privateKey,
    format: "der",
    type: "pkcs8",
  });

  const keyFingerprint = crypto
    .createHash("sha256")
    .update(
      Buffer.from(
        `0000000C${nodePublicKey
          .export({ format: "der", type: "spki" })
          // Ed25519 public key is the last 32 bytes of the SPKI DER key
          .subarray(-32)
          .toString("hex")}`,
        "hex"
      )
    )
    .digest("hex");
  const fingerprintPreFix = "1220"; // 12 PublicKeyFingerprint, 20 is a special length encoding
  const signedBy = `${fingerprintPreFix}${keyFingerprint}`;

  const hashBuffer = Buffer.from(preparedTransactionHash, "base64");
  const signedHash = crypto
    .sign(null, hashBuffer, {
      key: nodePrivateKey,
      dsaEncoding: "ieee-p1363",
    })
    .toString("base64");

  return {
    signedBy,
    signedHash,
  };
}
