// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  createLedgerApiClient,
  filtersByParty,
} from "../apis/ledger-api-utils";
import { CommandOptions } from "../cli";
import { HoldingInterface } from "../constants";
import {
  Command,
  DeduplicationPeriod2,
  PartySignatures,
} from "canton-json-api-v2-openapi";
import * as crypto from "crypto";
import dayjs from "dayjs";
import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import {
  createConfiguration,
  DefaultApi as TransferFactoryAPI,
  DisclosedContract,
  ServerConfiguration,
} from "transfer-instruction-openapi";

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
  userId: string;
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
    transferFactoryRegistryUrl,
  } = opts;
  const ledgerClient = createLedgerApiClient(opts);
  const transferRegistryConfig = createConfiguration({
    baseServer: new ServerConfiguration(transferFactoryRegistryUrl, {}),
  });
  const transferRegistryClient = new TransferFactoryAPI(transferRegistryConfig);

  const ledgerEndOffset = await ledgerClient.getV2StateLedgerEnd();
  const senderHoldings = await ledgerClient.postV2StateActiveContracts({
    filter: {
      filtersByParty: filtersByParty(sender, [HoldingInterface], false),
    },
    verbose: false,
    activeAtOffset: ledgerEndOffset.offset,
  });
  if (senderHoldings.length === 0) {
    throw new Error("Sender has no holdings, so transfer can't be executed.");
  }
  const holdings = senderHoldings.map(
    (h) => h["contractEntry"]["JsActiveContract"]
  );
  const inputHoldingCids = holdings.map((h) => h["createdEvent"]["contractId"]);

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
      meta: { values: {} },
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

  const command = new Command();
  command.ExerciseCommand = {
    templateId:
      "#splice-api-token-transfer-instruction-v1:Splice.Api.Token.TransferInstructionV1:TransferFactory",
    contractId: transferFactory.factoryId,
    choice: "TransferFactory_Transfer",
    choiceArgument: choiceArgs,
  };

  const disclosedContracts = transferFactory.choiceContext.disclosedContracts;

  const synchronizerId =
    getSynchronizerIdFromDisclosedContracts(disclosedContracts);

  const prepared = await ledgerClient.postV2InteractiveSubmissionPrepare({
    actAs: [sender],
    readAs: [sender],
    userId: opts.userId,
    commandId: `tscli-${randomUUID()}`,
    synchronizerId,
    commands: [command],
    disclosedContracts,
    verboseHashing: true,
    packageIdSelectionPreference: [],
  });

  const signed = signTransaction(
    publicKey,
    privateKey,
    prepared.preparedTransactionHash
  );
  const partySignatures: PartySignatures = {
    signatures: [
      {
        party: sender,
        signatures: [
          {
            signature: signed.signedHash,
            signedBy: signed.signedBy,
            // `unrecognizedValue`s are forced because of openapi generation, but it's not required (nor does it break anything)
            format: { SIGNATURE_FORMAT_RAW: {}, unrecognizedValue: 0 },
            signingAlgorithmSpec: {
              SIGNING_ALGORITHM_SPEC_ED25519: {},
              unrecognizedValue: 0,
            },
          },
        ],
      },
    ],
  };

  const deduplicationPeriod = new DeduplicationPeriod2();
  deduplicationPeriod.Empty = {};
  const result = await ledgerClient.postV2InteractiveSubmissionExecute({
    userId: opts.userId,
    submissionId: "",
    preparedTransaction: prepared.preparedTransaction,
    hashingSchemeVersion: prepared.hashingSchemeVersion,
    partySignatures,
    deduplicationPeriod,
  });

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
