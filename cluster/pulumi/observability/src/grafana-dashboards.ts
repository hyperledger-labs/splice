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

type DashboardVariable = {
  name: string;
  type: string;
  query?: string;
  current?: { text: string; value: string };
  options?: { selected: boolean; text: string; value: string }[];
};

function setDashboardConstants(content: string): string {
  const dashboard = JSON.parse(content);
  const variables: DashboardVariable[] = (dashboard.templating?.list ?? []).filter(
    (v: DashboardVariable) => v.type === 'constant' && v.name in dashboardConstants
  );
  if (variables.length === 0) {
    // keep the file as is to not reformat dashboards without such variables
    return content;
  }
  variables.forEach(v => {
    const value = dashboardConstants[v.name];
    v.query = value;
    v.current = { text: value, value };
    v.options = [{ selected: true, text: value, value }];
  });
  return JSON.stringify(dashboard, null, 2);
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
