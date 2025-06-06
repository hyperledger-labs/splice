import * as k8s from '@pulumi/kubernetes';

export function installStorageClasses(): void {
  // Follows https://cloud.google.com/kubernetes-engine/docs/how-to/persistent-volumes/hyperdisk
  new k8s.storage.v1.StorageClass('hyperdisk-balanced-rwo', {
    metadata: {
      name: 'hyperdisk-balanced-rwo',
    },
    provisioner: 'pd.csi.storage.gke.io',
    volumeBindingMode: 'WaitForFirstConsumer',
    parameters: {
      type: 'hyperdisk-balanced',
      'provisioned-throughput-on-create': '250Mi',
      'provisioned-iops-on-create': '7000',
    },
  });
}
