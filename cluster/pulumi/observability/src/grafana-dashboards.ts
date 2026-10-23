// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as fs from 'fs';
import * as path from 'path';
import { CLUSTER_BASENAME, GCP_PROJECT, SPLICE_ROOT } from '@canton-network/splice-pulumi-common';
import { Input } from '@pulumi/pulumi';

export function createGrafanaDashboards(namespace: Input<string>): void {
  createdNestedConfigMapForFolder(
    namespace,
    `${SPLICE_ROOT}/cluster/pulumi/observability/grafana-dashboards/`
  );
}

function createdNestedConfigMapForFolder(namespace: Input<string>, folderPath: string) {
  const dirFiles = fs.readdirSync(folderPath);
  dirFiles.forEach(file => {
    const filePath = path.join(folderPath, file);
    if (fs.statSync(filePath).isDirectory()) {
      createConfigMapForFolder(namespace, filePath, file.toLowerCase());
    }
  });
}

// Values of the cluster specific constant dashboard variables, overriding whatever value
// they have in the dashboard json.
const dashboardConstants: { [name: string]: string } = {
  project: GCP_PROJECT,
  cluster: CLUSTER_BASENAME,
};

// A variable of a dashboard in the V2 resource format
type DashboardVariable = {
  kind: string;
  spec: {
    name: string;
    query?: string;
    current?: { text: string; value: string };
  };
};

function setDashboardConstants(content: string): string {
  const dashboard = JSON.parse(content);
  const variables: DashboardVariable[] = dashboard.spec?.variables ?? [];
  variables
    .filter(v => v.kind === 'ConstantVariable' && v.spec.name in dashboardConstants)
    .forEach(v => {
      const value = dashboardConstants[v.spec.name];
      v.spec.query = value;
      v.spec.current = { text: value, value };
    });
  // Compact JSON keeps the ConfigMaps well below the 1 MiB limit
  return JSON.stringify(dashboard);
}

function createConfigMapForFolder(
  namespace: Input<string>,
  folderPath: string,
  folderName: string
) {
  const dirFiles = fs.readdirSync(folderPath);
  const files: { [key: string]: string } = {};
  dirFiles.forEach(file => {
    const filePath = path.join(folderPath, file);
    if (fs.statSync(filePath).isFile() && filePath.endsWith('.json')) {
      files[file] = setDashboardConstants(fs.readFileSync(filePath, 'utf-8'));
    }
  });
  new k8s.core.v1.ConfigMap(`grafana-dashboards-${folderName}`, {
    metadata: {
      name: `cn-grafana-dashboards-${folderName}`,
      namespace: namespace,
      labels: {
        grafana_dashboard: '1',
      },
      annotations: {
        folder: `/tmp/dashboards/${folderName}`,
      },
    },
    data: files,
  });
}
