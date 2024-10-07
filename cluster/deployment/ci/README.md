### Cluster for CircleCI self hosted runners

#### Manual changes

- On the GKE cluster we must approve the filestore CSI driver
- This cluster's egress must be whitelisted in clusters that must be deployed by the self hosted runners
- This cluster's egress must be whitelisted by the control panel of clusters that have to support any pulumi action from the runners
