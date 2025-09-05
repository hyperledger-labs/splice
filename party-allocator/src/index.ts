import { z } from "zod";
import { LedgerApiClient } from "./ledger-api-client.js";
import * as crypto from "node:crypto";
import { Command, DisclosedContract } from "canton-json-api-v2-openapi";

export type Conf = {
  token: string;
  jsonLedgerApiUrl: string;
  scanApiUrl: string;
  maxParties: number;
  keyDirectory: string;
};

const partyAllocationsSchema = z.object({
  token: z.string(),
  jsonLedgerApiUrl: z.string(),
  scanApiUrl: z.string(),
  validatorApiUrl: z.string(),
  maxParties: z.number(),
  keyDirectory: z.string(),
  parallelism: z.number().default(20),
});

type PartyAllocationsConf = z.infer<typeof partyAllocationsSchema>;

const config: PartyAllocationsConf = partyAllocationsSchema.parse(
  JSON.parse(process.env.EXTERNAL_CONFIG!),
);

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

async function getPreapproval(partyId: string) {
  const response = await fetch(
    `${config.scanApiUrl}/api/scan/v0/transfer-preapprovals/by-party/${partyId}`,
  );
  if (response.status === 404) {
    throw new Error(`No preapproval for ${partyId}`);
  }
  return response.json();
}

async function setupTopology(client: LedgerApiClient, synchronizerId: string, partyHint: string, keyPair: crypto.KeyPairSyncResult<Buffer, string>) {

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
  const allocatePartyResponse = await client.allocateExternalParty(
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
  return allocatePartyResponse.partyId;
}

async function tap(client: LedgerApiClient, synchronizerId: string, partyId: string, keyPair: crypto.KeyPairSyncResult<Buffer, string>) {
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

async function setupPreapproval(client: LedgerApiClient, synchronizerId: string, partyId: string, validatorPartyId: string, keyPair: crypto.KeyPairSyncResult<Buffer, string>) {
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
  await client.retry("getPreapproval", () => getPreapproval(partyId));
}

async function setupParty(client: LedgerApiClient, synchronizerId: string, partyHint: string, validatorPartyId: string) {
  await getOpenRound();
  const keyPair = crypto.generateKeyPairSync("ed25519", {
    publicKeyEncoding: { format: "der", type: "spki" },
    privateKeyEncoding: { format: "pem", type: "pkcs8" },
  });

  const partyId = await setupTopology(client, synchronizerId, partyHint, keyPair);

  await tap(client, synchronizerId, partyId, keyPair);
  await setupPreapproval(client, synchronizerId, partyId, validatorPartyId, keyPair);
}

async function main() {
  console.debug(`Running with config: ${JSON.stringify(config)}`);
  const synchronizerId = await getSynchronizerId();
  console.debug(`Synchronizer id: ${synchronizerId}`);
  const validatorPartyId = await getValidatorPartyId();
  console.debug(`Validator party id: ${validatorPartyId}`);
  const client = new LedgerApiClient(config.jsonLedgerApiUrl, config.token);
  setupParty(client, synchronizerId, 'thebestparty', validatorPartyId);
}

await main();

export {};
