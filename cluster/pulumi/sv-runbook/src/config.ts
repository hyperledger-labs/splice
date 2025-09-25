// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { configForSv, svRunbookConfig } from '@lfdecentralizedtrust/splice-pulumi-common-sv';

export type SvAppConfig = {
  onboardingName: string;
  disableOnboardingParticipantPromotionDelay: boolean;
  svIdKeyGcpSecret: string;
  externalGovernanceKeyGcpSecret?: string;
};

export type ValidatorAppConfig = {
  walletUserName: string;
};

export function buildSvAppConfig(disableOnboardingParticipantPromotionDelay: boolean): SvAppConfig {
  const name = svRunbookConfig.nodeName;
  const configFromYaml = configForSv(name);
  const svIdKeyGcpSecret = configFromYaml?.svApp?.svIdKeyGcpSecret ?? name.replace('-', '') + '-id';
  const externalGovernanceKeyGcpSecret = configFromYaml?.participant?.kms
    ? (configFromYaml?.svApp?.cometBftGovernanceKeyGcpSecret ??
      name.replace('-', '') + '-cometbft-governance-key')
    : undefined;

  return {
    onboardingName: svRunbookConfig.onboardingName,
    disableOnboardingParticipantPromotionDelay,
    svIdKeyGcpSecret,
    externalGovernanceKeyGcpSecret,
  };
}
