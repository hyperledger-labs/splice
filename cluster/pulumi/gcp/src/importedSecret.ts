// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as pulumi from '@pulumi/pulumi';

export class ImportedSecret extends pulumi.ComponentResource {
  constructor(
    name: string,
    args: {
      sourceProject: string;
      secretId: string;
    },
    opts: pulumi.CustomResourceOptions
  ) {
    super('cn:gcp:ImportedSecret', name, {}, opts);

    const source = gcp.secretmanager.getSecretVersionOutput(
      { secret: name, project: args.sourceProject },
      opts
    );

    const secret = new gcp.secretmanager.Secret(
      `${name}-secret`,
      { secretId: name, replication: { auto: {} } },
      opts
    );

    const secretVersion = new gcp.secretmanager.SecretVersion(
      `${name}-secretversion`,
      {
        secret: secret.id,
        secretData: source.secretData,
      },
      opts
    );

    this.registerOutputs({ secret, secretVersion });
  }
}
