import * as pulumi from '@pulumi/pulumi';

export type PersistenceConfig = {
  host: pulumi.Output<string>;
  port: pulumi.Output<number>;
  schema: pulumi.Output<string>;
  user: pulumi.Output<string>;
  password: pulumi.Output<string>;
};
