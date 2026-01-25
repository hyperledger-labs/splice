// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
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

    const iamMappings = roles.map(role =>
      _addRoleToGcpServiceAccount(name, account.project, account.member, role, opts)
    );
    this.registerOutputs({ account, iamMappings });
  }
}

export function addRoleToGcpServiceAccount(
  accountPulumiName: string,
  projectId: string,
  accountEmail: string,
  role: Role,
  opts?: pulumi.CustomResourceOptions
): gcp.projects.IAMMember {
  return _addRoleToGcpServiceAccount(
    accountPulumiName,
    pulumi.output(projectId),
    pulumi.output(`serviceAccount:${accountEmail}`),
    role,
    opts
  );
}

function _addRoleToGcpServiceAccount(
  accountPulumiName: string,
  projectId: pulumi.Output<string>,
  accountMember: pulumi.Output<string>,
  role: Role,
  opts?: pulumi.CustomResourceOptions
): gcp.projects.IAMMember {
  const roleName = typeof role === 'string' ? role : role.id;
  const condition = typeof role === 'string' ? undefined : role.condition;

  return new gcp.projects.IAMMember(
    `${accountPulumiName}-${roleToPulumiName(role)}-iam`,
    {
      project: projectId,
      member: accountMember,
      condition,
      role: roleName,
    },
    opts
  );
}
