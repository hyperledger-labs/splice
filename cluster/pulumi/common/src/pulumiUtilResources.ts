// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import { Output } from '@pulumi/pulumi';

export class SplicePlaceholderResource extends pulumi.CustomResource {
  readonly name: Output<string>;

  constructor(name: string) {
    super('splice:placeholder', name, {}, {}, true);
    this.name = Output.create(name);
  }
}
