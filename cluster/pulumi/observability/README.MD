## The observability project

This pulumi project deploys
the [kube-prometheus-stack](https://github.com/prometheus-community/helm-charts/tree/main/charts/kube-prometheus-stack)
that installs prometheus, grafana alongside a number of predefined grafana dashboards.

### Ingresses

To access the deployment we create one Istio virtual service for grafana and one for prometheus.
This allows us to access the deployments at `grafana.<CLUSTER_DNS>`, `prometheus.<CLUSTER_DNS>`.

### Grafana dashboards

We deploy a number of extra grafana dashboards that are built by the platform-enablement team and are part of the
open source observability
example [here](https://github.com/digital-asset/daml-platform-observability-example/tree/main/grafana/dashboards).

Check `grafana-dashboards.nix` to see how we get the dashboards.

#### Ad hoc filter added

As part of getting the dashboards, in `grafana-dashboards.nix`, we add an ad hoc filter using the `build_num` label with
a default value of `non_existing`.
This is done specifically for the CI deployment, and it should be removed for any other deployment.
The main reason is to prevent loading a lot of metric series when first opening the dashboard (as all the metrics are
labeled for each CI run job with a different `build_num`, this would load a lot of series in prometheus leading to
really high memory usage), and allowing us to filter
for a specific `build_num` much easier.

This filter will in turn have the side effect of not having any data when first opening a dashboard, but the user would
have to select an existing build num and adjust the time period to match the time of the CI run for the data to be
fetched.
