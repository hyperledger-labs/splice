import * as gcp from '@pulumi/gcp';
import * as pulumi from '@pulumi/pulumi';

type Role =
  | string
  | { id: string; condition: { title: string; description: string; expression: string } };

const roleToPulumiName = (role: Role): string => {
  if (typeof role === 'string') {
    return role;
  } else {
    return `${role.id}-${role.condition.title.toLocaleLowerCase().replaceAll(' ', '-')}`;
  }
};

export class GcpServiceAccount extends pulumi.ComponentResource {
  name: pulumi.Output<string>;

  constructor(
    name: string,
    args: {
      roles: Role[];
      accountId: string;
      displayName: string;
      description: string;
    },
    opts?: pulumi.CustomResourceOptions
  ) {
    super('cn:gcp:ServiceAccount', name, {}, opts);
    const { roles, ...gcpArgs } = args;

    const account = new gcp.serviceaccount.Account(`${name}-sa`, gcpArgs, opts);
    this.name = account.name;

    roles.forEach(r => {
      const role = typeof r === 'string' ? r : r.id;
      const condition = typeof r === 'string' ? undefined : r.condition;

      new gcp.projects.IAMMember(
        `${name}-${roleToPulumiName(r)}-iam`,
        {
          project: account.project,
          member: account.member,
          condition,
          role,
        },
        opts
      );
    });

    this.registerOutputs({ account });
  }
}
