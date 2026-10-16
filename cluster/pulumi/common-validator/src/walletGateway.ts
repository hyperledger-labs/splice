// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as postgres from '@canton-network/splice-pulumi-common/src/postgres';
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import {
  Auth0Client,
  CLUSTER_HOSTNAME,
  CnInput,
  ExactNamespace,
  HELM_CHART_TIMEOUT_SEC,
  HELM_MAX_HISTORY_SIZE,
  InstalledHelmChart,
  LogLevel,
  appsKubernetesScheduling,
  getNamespaceConfig,
  installWalletGatewayAdminSecret,
  spliceConfig,
} from '@canton-network/splice-pulumi-common';
import { SplicePlaceholderResource } from '@canton-network/splice-pulumi-common/src/pulumiUtilResources';

import { WalletGatewayConfig } from './config';

export async function installWalletGateway(
  auth0Client: Auth0Client,
  xns: ExactNamespace,
  config: WalletGatewayConfig,
  participantAddress: pulumi.Output<string> | string,
  wgPostgres: postgres.Postgres,
  logLevel: LogLevel | undefined,
  // The gateway talks to the participant's ledger API on startup, so sequence it after the validator
  dependsOn: CnInput<pulumi.Resource>[] = []
): Promise<InstalledHelmChart> {
  const ns = xns.logicalName;
  const auth0Cfg = auth0Client.getCfg();
  const nsAuth0 = getNamespaceConfig(auth0Cfg, ns);
  if (!nsAuth0.uiClientIds?.walletGateway) {
    throw new Error(`No wallet gateway Auth0 client configured for namespace ${ns}`);
  }

  const publicUrl = `https://walletgateway.${ns}.${CLUSTER_HOSTNAME}`;
  const portfolioUrl = `https://portfolio.${ns}.${CLUSTER_HOSTNAME}`;
  const scanUrl = `https://scan.sv-2.${CLUSTER_HOSTNAME}`;

  const adminSecret = await installWalletGatewayAdminSecret(auth0Client, xns);

  if (spliceConfig.pulumiProjectConfig.installDataOnly) {
    return new SplicePlaceholderResource('wallet-gateway');
  }

  const gateway = new k8s.helm.v3.Release(
    `${ns}-wallet-gateway`,
    {
      name: 'wallet-gateway',
      chart: 'oci://ghcr.io/digital-asset/wallet-gateway/helm/wallet-gateway',
      version: config.version,
      namespace: xns.ns.metadata.name,
      timeout: HELM_CHART_TIMEOUT_SEC,
      maxHistory: HELM_MAX_HISTORY_SIZE,
      values: {
        ...appsKubernetesScheduling,
        oauthSecrets: {
          OAUTH2_ADMIN_CLIENT_SECRET: {
            secretRef: { name: adminSecret.metadata.name, key: 'client-secret' },
          },
        },
        extraEnv: [
          {
            name: 'WG_STORE_PASSWORD',
            valueFrom: {
              secretKeyRef: { name: wgPostgres.secretName, key: 'postgresPassword' },
            },
          },
        ],
        config: {
          ...(logLevel ? { logging: { level: logLevel.toLowerCase() } } : {}),
          kernel: {
            id: `splice-${ns}`,
            clientType: 'remote',
            publicUrl,
          },
          // The chart schema requires all of these; upstream defaults except requestSizeLimit
          // and trustProxy (one hop: the istio ingress gateway)
          server: {
            port: 3030,
            allowedOrigins: [portfolioUrl],
            dappPath: '/api/v0/dapp',
            userPath: '/api/v0/user',
            requestSizeLimit: '5mb',
            requestRateLimit: 10000,
            trustProxy: 1,
            signingWorker: { pollInterval: 5000 },
          },
          store: {
            connection: {
              type: 'postgres',
              host: wgPostgres.address,
              port: 5432,
              user: wgPostgres.userName,
              passwordEnv: 'WG_STORE_PASSWORD',
              database: 'wg_store',
            },
          },
          signingStore: {
            connection: {
              type: 'postgres',
              host: wgPostgres.address,
              port: 5432,
              user: wgPostgres.userName,
              passwordEnv: 'WG_STORE_PASSWORD',
              database: 'wg_signing_store',
            },
          },
          bootstrap: {
            idps: [
              {
                id: 'idp-auth0',
                type: 'oauth',
                issuer: `https://${auth0Cfg.auth0Domain}/`,
                configUrl: `https://${auth0Cfg.auth0Domain}/.well-known/openid-configuration`,
              },
            ],
            networks: [
              {
                id: `canton:${ns}`,
                name: `Splice ${ns}`,
                description: `Splice ${ns} wallet gateway network`,
                identityProviderId: 'idp-auth0',
                ledgerApi: { baseUrl: pulumi.interpolate`http://${participantAddress}:7575` },
                auth: {
                  method: 'authorization_code',
                  audience: nsAuth0.audiences.ledgerApi,
                  scope: 'openid daml_ledger_api offline_access',
                  clientId: nsAuth0.uiClientIds.walletGateway,
                },
                // Reuses the validator backend client, i.e. the participant admin user, so the
                // gateway can allocate parties and grant user rights
                adminAuth: {
                  method: 'client_credentials',
                  audience: nsAuth0.audiences.ledgerApi,
                  scope: 'daml_ledger_api',
                  clientId: nsAuth0.backendClientIds.validator,
                  clientSecretEnv: 'OAUTH2_ADMIN_CLIENT_SECRET',
                },
              },
            ],
          },
        },
      },
    },
    { dependsOn }
  );

  new k8s.helm.v3.Release(
    `${ns}-portfolio`,
    {
      name: 'portfolio',
      chart: 'oci://ghcr.io/digital-asset/splice-portfolio/helm/splice-portfolio',
      version: config.portfolioVersion,
      namespace: xns.ns.metadata.name,
      timeout: HELM_CHART_TIMEOUT_SEC,
      maxHistory: HELM_MAX_HISTORY_SIZE,
      values: {
        ...appsKubernetesScheduling,
        config: {
          amulet: {
            validatorUrl: `https://wallet.${ns}.${CLUSTER_HOSTNAME}/api/validator`,
            registry: scanUrl,
          },
          token: {
            validatorUrl: `https://wallet.${ns}.${CLUSTER_HOSTNAME}/api/validator`,
            registries: [{ url: scanUrl, name: 'Amulet registry' }],
          },
        },
      },
    },
    { dependsOn }
  );

  return gateway;
}
