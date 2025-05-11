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
