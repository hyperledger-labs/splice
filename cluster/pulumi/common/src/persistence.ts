import * as pulumi from '@pulumi/pulumi';

export type PersistenceConfig = {
  host: pulumi.Output<string>;
  port: pulumi.Output<number>;
  createDb: boolean;
  databaseName: pulumi.Output<string>;
  secretName: string;
  schema: pulumi.Output<string>;
  user: pulumi.Output<string>;
};
