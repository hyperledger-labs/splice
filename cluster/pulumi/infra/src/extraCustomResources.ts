import * as k8s from '@pulumi/kubernetes';

export function installExtraCustomResources(
  extraCrs: Record<string, k8s.apiextensions.CustomResourceArgs>
): void {
  Object.entries(extraCrs).forEach(([name, spec]) => {
    new k8s.apiextensions.CustomResource(name, spec);
  });
}
