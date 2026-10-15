// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { exactNamespace, type Auth0Client } from "@canton-network/splice-pulumi-common";

export async function installNode(
  auth0Client: Auth0Client,
  namespace: string,
): Promise<void> {
  const xns = exactNamespace(namespace, true);
}
