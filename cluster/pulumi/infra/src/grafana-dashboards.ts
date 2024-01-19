import * as k8s from '@pulumi/kubernetes';
import * as fs from 'fs';
import * as path from 'path';
import { Input } from '@pulumi/pulumi';
import { REPO_ROOT } from 'cn-pulumi-common';

const dashboardsPath = process.env.GRAFANA_DASHBOARDS;

export function createGrafanaDashboards(namespace: Input<string>, filtered: boolean): void {
  const prefix = filtered ? 'filtered-' : '';
  createConfigMapForFolder(namespace, `${dashboardsPath}/${prefix}platform`, 'platform');
  createConfigMapForFolder(namespace, `${dashboardsPath}/${prefix}participant`, 'participant');
  createConfigMapForFolder(namespace, `${dashboardsPath}/${prefix}canton`, 'canton');
  createConfigMapForFolder(
    namespace,
    `${REPO_ROOT}/cluster/pulumi/infra/grafana-dashboards`,
    'canton-network'
  );
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
    if (fs.statSync(filePath).isFile()) {
      files[file] = fs.readFileSync(filePath, 'utf-8');
    }
  });
  new k8s.core.v1.ConfigMap(`grafana-dashboards-${folderName}`, {
    metadata: {
      name: `grafana-dashboards-${folderName}`,
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
