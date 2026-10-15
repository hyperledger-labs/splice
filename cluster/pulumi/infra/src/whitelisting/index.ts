// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';

import { infraConfig } from '../config';
import { configureGatewayAccessPolicies } from './gateway';
import { configureScanAndSvAppWhitelist } from './scanAndSvApp';
import { configureSequencerWhitelist } from './sequencer';

export function installAppWhitelisting(
  namespace: k8s.core.v1.Namespace
): pulumi.Output<pulumi.Resource[]>[] {
  return [...configureScanAndSvAppWhitelist(namespace), ...configureSequencerWhitelist(namespace)];
}

export function configureIstioGatewayPolicies(
  ingressNs: k8s.core.v1.Namespace,
  externalIPRangesInIstio: pulumi.Output<string[]>,
  suffix: string
): pulumi.Output<pulumi.Resource[]> {
  return configureGatewayAccessPolicies(ingressNs, externalIPRangesInIstio, suffix);
}
