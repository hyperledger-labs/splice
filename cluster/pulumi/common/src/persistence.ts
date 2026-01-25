// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';

export type PersistenceConfig = {
  host: pulumi.Output<string>;
  port: pulumi.Output<number>;
  databaseName: pulumi.Output<string>;
  secretName: pulumi.Output<string>;
  schema: pulumi.Output<string>;
  user: pulumi.Output<string>;
  postgresName: string;
};
