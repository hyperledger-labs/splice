import * as pulumi from '@pulumi/pulumi';
import { Output } from '@pulumi/pulumi';

export class PulumiPlaceholderResource extends pulumi.CustomResource {
  readonly name: Output<string>;

  constructor(name: string) {
    super('splice:placeholder', name);
    this.name = Output.create(name);
  }
}
