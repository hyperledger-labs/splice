import * as pulumi from '@pulumi/pulumi';
import { Output } from '@pulumi/pulumi';

export class SplicePlaceholderResource extends pulumi.CustomResource {
  readonly name: Output<string>;

  constructor(name: string) {
    super('splice:placeholder', name, {}, {}, true);
    this.name = Output.create(name);
  }
}
