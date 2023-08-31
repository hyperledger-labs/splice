import * as k8s from '@pulumi/kubernetes';
import * as fs from 'fs';
import * as path from 'path';
import { Input } from '@pulumi/pulumi';

const dashboardsPath = process.env.GRAFANA_DASHBOARDS;

export function createGrafanaDashboards(namespace: Input<string>): void {
  createConfigMapForFolder(namespace, 'platform');
  createConfigMapForFolder(namespace, 'participant');
}

function createConfigMapForFolder(namespace: Input<string>, folder: string) {
  const folderPath = `${dashboardsPath}/${folder}`;
  const dirFiles = fs.readdirSync(folderPath);
  const files: { [key: string]: string } = {};
  dirFiles.forEach(file => {
    const filePath = path.join(folderPath, file);
    if (fs.statSync(filePath).isFile()) {
      files[file] = fs.readFileSync(filePath, 'utf-8');
    }
  });
  new k8s.core.v1.ConfigMap(`grafana-dashboards-${folder}`, {
    metadata: {
      name: `grafana-dashboards-${folder}`,
      namespace: namespace,
      labels: {
        grafana_dashboard: '1',
      },
      annotations: {
        folder: `/tmp/dashboards/${folder}`,
      },
    },
    data: files,
  });
}
