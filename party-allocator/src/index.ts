// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { LedgerApiClient } from "./ledger-api-client.js";
import * as crypto from "node:crypto";
import {
  Command,
  DisclosedContract,
} from "@lfdecentralizedtrust/canton-json-api-v2-openapi";
import { readdir, writeFile } from "node:fs/promises";
import { config } from "./config.js";
import fs from "fs";
import { logger } from "./logger.js";

async function getAmuletRules() {
  const response = await fetch(
    `${config.scanApiUrl}/api/scan/v0/amulet-rules`,
    {
      method: "POST",
      body: "{}",
      headers: { "content-type": "application/json" },
    },
  );
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  return ((await response.json()) as any).amulet_rules_update;
}

async function getSynchronizerId() {
  const amuletRules = await getAmuletRules();
  return amuletRules.contract.payload.configSchedule.initialValue
    .decentralizedSynchronizer.activeSynchronizer;
}

async function getOpenRound() {
  const response = await fetch(
    `${config.scanApiUrl}/api/scan/v0/open-and-issuing-mining-rounds`,
    {
      method: "POST",
      body: '{"cached_open_mining_round_contract_ids": [], "cached_issuing_round_contract_ids": []}',
      headers: { "content-type": "application/json" },
    },
  );
  const body = await response.json();
  return Object.values(body.open_mining_rounds)
    .filter((x: any) => new Date(x.contract.payload.opensAt) < new Date())
    .sort(
      (a: any, b: any) =>
        b.contract.payload.round.number - a.contract.payload.round.number,
    )[0] as any;
}

async function getValidatorPartyId() {
  const response = await fetch(
    `${config.validatorApiUrl}/api/validator/v0/validator-user`,
  );
  const body = await response.json();
  return body.party_id;
}

function toDisclosedContract(c: any): DisclosedContract {
  return {
    templateId: c.contract.template_id,
    contractId: c.contract.contract_id,
    createdEventBlob: c.contract.created_event_blob,
    synchronizerId: c.domain_id,
  };
}

async function getPreapproval(client: LedgerApiClient, partyId: string) {
  const response = await client.queryContracts(
    [partyId],
    ["#splice-amulet:Splice.AmuletRules:TransferPreapproval"],
  );
  if (response.length > 0) {
    return response[0];
  }
  throw new Error(`No preapproval for ${partyId}`);
}

async function setupTopology(
  client: LedgerApiClient,
  synchronizerId: string,
  partyHint: string,
  keyPair: crypto.KeyPairSyncResult<Buffer, string>,
) {
  const generateTopologyResponse = await client.generateExternalPartyTopology(
    synchronizerId,
    partyHint,
    {
      format: "CRYPTO_KEY_FORMAT_DER_X509_SUBJECT_PUBLIC_KEY_INFO",
      keyData: keyPair.publicKey.toString("base64"),
      keySpec: "SIGNING_KEY_SPEC_EC_CURVE25519",
    },
  );
  const signature = crypto.sign(
    null,
    Buffer.from(generateTopologyResponse.multiHash, "base64"),
    keyPair.privateKey,
  );
  await client.allocateExternalParty(
    generateTopologyResponse.partyId,
    synchronizerId,
    generateTopologyResponse.topologyTransactions!.map((t) => ({
      transaction: t,
      signatures: [],
    })),
    [
      {
        format: "SIGNATURE_FORMAT_RAW",
        signature: signature.toString("base64"),
        signedBy: generateTopologyResponse.partyId.split("::")[1],
        signingAlgorithmSpec: "SIGNING_ALGORITHM_SPEC_ED25519",
      },
    ],
  );
  return generateTopologyResponse.partyId;
}

async function tap(
  client: LedgerApiClient,
  synchronizerId: string,
  partyId: string,
  keyPair: crypto.KeyPairSyncResult<Buffer, string>,
) {
  const amuletRules = await getAmuletRules();
  const round = await getOpenRound();

  const command = new Command();
  command.ExerciseCommand = {
    templateId: "#splice-amulet:Splice.AmuletRules:AmuletRules",
    choice: "AmuletRules_DevNet_Tap",
    contractId: amuletRules.contract.contract_id,
    choiceArgument: {
      receiver: partyId,
      amount: "100.0",
      openRound: round.contract.contract_id,
    },
  };
  return client.submitTransaction(
    "tap",
    synchronizerId,
    partyId,
    keyPair.privateKey,
    [amuletRules, round].map(toDisclosedContract),
    command,
  );
}

async function setupPreapproval(
  client: LedgerApiClient,
  synchronizerId: string,
  partyId: string,
  validatorPartyId: string,
  keyPair: crypto.KeyPairSyncResult<Buffer, string>,
) {
  const amuletRules = await getAmuletRules();

  const command2 = new Command();
  command2.CreateCommand = {
    templateId:
      "#splice-wallet:Splice.Wallet.TransferPreapproval:TransferPreapprovalProposal",
    createArguments: {
      receiver: partyId,
      provider: validatorPartyId,
      expectedDso: amuletRules.contract.payload.dso,
    },
  };
  await client.submitTransaction(
    "create preapproval proposal",
    synchronizerId,
    partyId,
    keyPair.privateKey,
    [],
    command2,
  );
  await client.retry("getPreapproval", () => getPreapproval(client, partyId));
}

function pubKeyPath(index: number) {
  return `${config.keyDirectory}/${index}_pub.key`;
}

function privKeyPath(index: number) {
  return `${config.keyDirectory}/${index}_priv.key`;
}

async function generateKeyPair(index: number) {
  const keyPair = crypto.generateKeyPairSync("ed25519", {
    publicKeyEncoding: { format: "der", type: "spki" },
    privateKeyEncoding: { format: "pem", type: "pkcs8" },
  });
  await writeFile(pubKeyPath(index), keyPair.publicKey);
  await writeFile(privKeyPath(index), keyPair.privateKey);
  return keyPair;
}

async function setupParty(
  client: LedgerApiClient,
  userId: string,
  synchronizerId: string,
  index: number,
  validatorPartyId: string,
) {
  logger.info(`Starting setup for party ${index}`);
  const partyHint = `party-${index}`;
  await getOpenRound();
  const keyPair = await generateKeyPair(index);

  const partyId = await setupTopology(
    client,
    synchronizerId,
    partyHint,
    keyPair,
  );

  await client.withUserRights(userId, [partyId], async () => {
    await tap(client, synchronizerId, partyId, keyPair);
    await setupPreapproval(
      client,
      synchronizerId,
      partyId,
      validatorPartyId,
      keyPair,
    );
  });
  logger.info(`Finished setup for party ${index}`);
}

async function main() {
  logger.info(
    `Running with config: ${JSON.stringify({ ...config, ...{ token: "<redacted>" } })}`,
  );
  const synchronizerId = await getSynchronizerId();
  logger.info(`Synchronizer id: ${synchronizerId}`);
  const validatorPartyId = await getValidatorPartyId();
  logger.info(`Validator party id: ${validatorPartyId}`);
  if (!fs.existsSync(config.keyDirectory)) {
    fs.mkdirSync(config.keyDirectory);
  }
  const contents = await readdir(config.keyDirectory);
  const keyIndices = contents.map((f) => {
    const match = f.match(/(?<index>.*)_priv.key/);
    return parseInt(match?.groups?.index || "0");
  });
  const maxIndex = keyIndices.length > 0 ? Math.max(...keyIndices) + 1 : 0;
  // We just reinitialize the party at maxIndex + 1 from scratch instead of trying to clever
  // and incrementally handle all kinds of failures.
  logger.info(`Starting at ${maxIndex}`);

  const client = new LedgerApiClient(config.jsonLedgerApiUrl, config.token);

  let index = maxIndex;
  while (index < config.maxParties) {
    logger.info(`Processing batch starting at ${index}`);
    const batchSize = Math.min(config.parallelism, config.maxParties - index);
    const batch = Array.from({ length: batchSize }, (_, i) =>
      setupParty(
        client,
        config.userId,
        synchronizerId,
        index + i,
        validatorPartyId,
      ),
    );
    await Promise.all(batch);
    logger.info(`Completed batch`);
    index += batchSize;
  }
  logger.info(`Party allocator, completed. Sleeping`);
  // sleep forever so k8s doesn't restart it over and over.
  // For some reason, nodejs is too smart and await new Promise(() => {}) does not actually work.
  await sleepForever();
}

async function sleepForever() {
  await new Promise((resolve) => setInterval(() => resolve(1000 * 60 * 60)));
  sleepForever;
}

await main();

export {};
