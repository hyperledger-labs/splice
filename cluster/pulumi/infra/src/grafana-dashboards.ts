// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as fs from 'fs';
import * as path from 'path';
import { Input } from '@pulumi/pulumi';
import { SPLICE_ROOT } from 'splice-pulumi-common';

export function createGrafanaDashboards(namespace: Input<string>): void {
  createdNestedConfigMapForFolder(
    namespace,
    `${SPLICE_ROOT}/cluster/pulumi/infra/grafana-dashboards/`
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
      files[file] = fs.readFileSync(filePath, 'utf-8');
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
