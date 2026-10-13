// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import z from "zod";

// Mirrors the auth config of the Splice apps, see AuthTokenSourceConfig.scala.
const staticAuthSchema = z.object({
  type: z.literal("static"),
  token: z.string(),
});

const clientCredentialsAuthSchema = z.object({
  type: z.literal("client-credentials"),
  // URL for the well-known OpenID configuration, see https://openid.net/specs/openid-connect-discovery-1_0.html
  wellKnownConfigUrl: z.string(),
  clientId: z.string(),
  clientSecret: z.string(),
  audience: z.string(),
  // Not all IAMs require a scope so this is optional.
  scope: z.string().optional(),
});

const authSchema = z.discriminatedUnion("type", [
  staticAuthSchema,
  clientCredentialsAuthSchema,
]);

export type AuthConfig = z.infer<typeof authSchema>;
export type ClientCredentialsAuthConfig = z.infer<
  typeof clientCredentialsAuthSchema
>;

const partyAllocationsSchema = z.object({
  auth: authSchema,
  userId: z.string(),
  jsonLedgerApiUrl: z.string(),
  scanApiUrl: z.string(),
  validatorApiUrl: z.string(),
  maxParties: z.number(),
  keyDirectory: z.string(),
  parallelism: z.number().default(20),
  preapprovalRetries: z.number().default(120),
  preapprovalRetryDelayMs: z.number().default(1000),
});

type PartyAllocationsConf = z.infer<typeof partyAllocationsSchema>;

if (!process.env.EXTERNAL_CONFIG) {
  throw new Error("EXTERNAL_CONFIG envrionment variable must be set");
}

export const config: PartyAllocationsConf = partyAllocationsSchema.parse(
  JSON.parse(process.env.EXTERNAL_CONFIG!),
);

// Config with all secrets redacted, safe to log.
export function redactedConfig(conf: PartyAllocationsConf): unknown {
  const auth: AuthConfig =
    conf.auth.type === "static"
      ? { ...conf.auth, token: "<redacted>" }
      : { ...conf.auth, clientSecret: "<redacted>" };
  return { ...conf, auth };
}
