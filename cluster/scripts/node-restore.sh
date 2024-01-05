#!/usr/bin/env bash

# TODO(#8920): For now, we do everything under the feet of Pulumi, which is a bad idea.

set -euo pipefail

# shellcheck disable=SC1091
source "${TOOLS_LIB}/libcli.source"

declare -A component_to_deployment
component_to_deployment["sequencer"]="global-domain-default-sequencer"
component_to_deployment["mediator"]="global-domain-default-mediator"
component_to_deployment["participant"]="participant"
component_to_deployment["cometbft"]="cometbft"
component_to_deployment["validator"]="validator-app"
component_to_deployment["sv"]="sv-app"
component_to_deployment["scan"]="scan-app"


function get_postgres_type() {
  local instance=$1

  cncluster pulumi canton-network stack export | grep -v "Running Pulumi Command" | jq -r ".deployment.resources[] | select(.urn | test(\".*canton:.*:postgres::${instance}\")) | .type"
}

function get_cloudsql_id() {
  local instance=$1

  cncluster pulumi canton-network stack export | grep -v "Running Pulumi Command" | jq -r ".deployment.resources[] | select(.urn | test(\".*DatabaseInstance::${instance}\")) | .id"
  # TODO(#8920): the component might actually be using a different database from that deployed in pulumi, e.g. if already recovered from backup once.
  # Or maybe we will actually import that restored DB into pulumi?
}

function create_pvc_from_snapshot() {
  local -r snapshot_name=$1
  local -r pvc_name=$2

  vs=$(kubectl get volumesnapshot -n "$namespace" -o json "$snapshot_name")
  status=$(echo "$vs" | jq -r .status.readyToUse)
  if [ "$status" != "true" ]; then
    _error "Snapshot $snapshot_name is not ready to use"
  fi
  restore_size=$(echo "$vs" | jq -r .status.restoreSize)

  _info "Creating PVC $pvc_name from snapshot $snapshot_name"
  kubectl apply -n "$namespace" -f - <<EOF
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: "$pvc_name"
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: "$restore_size"
  storageClassName: standard-rwo
  volumeMode: Filesystem
  dataSource:
    name: "$snapshot_name"
    kind: VolumeSnapshot
    apiGroup: snapshot.storage.k8s.io
EOF
}

function use_restored_pvc_in_cometbft() {
  local -r pvc_name=$1

  _info "Patching cometbft deployment to use the restored PVC $pvc_name"
  kubectl patch -n "$namespace" deployment cometbft --patch-file /dev/stdin <<EOF
spec:
  template:
    spec:
      volumes:
        - name: data
          persistentVolumeClaim:
            claimName: "$pvc_name"
EOF
}

function down() {
  local -r component=$1
  local -r deployment_name="${component_to_deployment[$component]}"

  _info "Scaling down $component deployment"
  kubectl scale deployment -n "$namespace" "$deployment_name" --replicas=0
}

function up() {
  local -r component=$1
  local -r deployment_name="${component_to_deployment[$component]}"

  _info "Scaling up $component deployment"
  kubectl scale deployment -n "$namespace" "$deployment_name" --replicas=1
}

function restore_pvc_postgres() {
  local -r component=$1
  local -r deployment_name="${component_to_deployment[$component]}"

  local -r template_name="pg-data"
  local -r helm_name="$component-pg"
  local -r pg_pod_name="$helm_name-0"

  _info "Creating PVC from snapshot"
  create_pvc_from_snapshot "$template_name-$pg_pod_name-$run_id" "$template_name-$run_id-restore-$pg_pod_name"

  # Stateful sets cannot be patched, so we need to uninstall and install the postgres helm chart.
  # We first get the current values, and replace the PVC template name with the restored PVC.
  _info "Uninstalling $component Postgres"
  values=$(helm get values -n "$namespace" "$helm_name" | sed "s/pvcTemplateName:.*/pvcTemplateName: $template_name-$run_id-restore/")
  helm uninstall -n "$namespace" "$helm_name"

  _info "Installing $component Postgres"
  # TODO(#8920): support helm charts from artifactory
  helm install -n "$namespace" "$helm_name" "$REPO_ROOT"/cluster/helm/target/cn-postgres-0.1.1-*.tgz -f /dev/stdin < <(echo "$values")
}

function patch_deploymend_env_var() {
  local -r component=$1
  local -r env_var_name=$2
  local -r env_var_value=$3

  local -r deployment_name="${component_to_deployment[$component]}"

  kubectl get deploy -o json -n "$namespace" "$deployment_name" | \
    jq -r ".spec.template.spec.containers |= (map(. | .env |= (. | map((select(.name==\"$env_var_name\").value) |= \"$env_var_value\" ))))" | \
    kubectl replace -f -
}

function patch_deployment_postgres_ip() {
  local -r component=$1
  local -r instance_ip=$2

  case "$component" in
    "participant")
      _info "Configuring participant to use postgres at IP $instance_ip"
      patch_deploymend_env_var "$component" "CANTON_PARTICIPANT_POSTGRES_SERVER" "$instance_ip"
      ;;
    "sequencer"|"mediator")
      _info "Configuring $component to use postgres at IP $instance_ip"
      patch_deploymend_env_var "$component" "CANTON_DOMAIN_POSTGRES_SERVER" "$instance_ip"
      ;;
    "validator"|"sv"|"scan")
      _info "Configuring $component to use postgres at IP $instance_ip"
      local -r deployment_name="${component_to_deployment[$component]}"
      persistence_config=$(kubectl get deploy -n "$namespace" "$deployment_name" -o json | \
        jq -r '.spec.template.spec.containers | map(.env[] | select(.name == "ADDITIONAL_CONFIG_PERSISTENCE")) | .[0] | .value')
      _info "Current persistence config: $persistence_config"
      # shellcheck disable=SC2001
      persistence_config_patched=$(echo "$persistence_config" | sed "s/serverName = \"[^\s]*\"/serverName = \"$instance_ip\"/g")
      persistence_config_patched=${persistence_config_patched//\"/\\\"}
      _info "Patched persistence config: $persistence_config_patched"
      patch_deploymend_env_var "$component" "ADDITIONAL_CONFIG_PERSISTENCE" "$persistence_config_patched"
      ;;
    *)
      _error "Patching component $component not yet implemented"
  esac
}

function restore_cloudsql_postgres() {
  local -r component=$1

  cloudsql_id=$(get_cloudsql_id "$namespace-$component-pg")
  backup_id=$(gcloud sql backups list --instance "$cloudsql_id" --filter="description=\"$run_id\"" --format=json | jq -r '.[].id')

  _info "Cloning CloudSQL DB instance $cloudsql_id"
  restored_instance_id="$cloudsql_id-$run_id-restore"
  gcloud sql instances clone "$cloudsql_id" "$restored_instance_id"
  # TODO(#8920): make this asynchronous
  # TODO(#8920): we don't really need a clone, just a new DB configured similarly to the original one.

  _info "Restoring CloudSQL DB instance $restored_instance_id from backup $backup_id"
  gcloud sql backups restore "$backup_id" --restore-instance="$restored_instance_id" --backup-instance="$cloudsql_id" --quiet
  # TODO(#8920): make this asynchronous

  instance_ip=$(gcloud sql instances list --filter NAME="$restored_instance_id" --format=json | jq -r '.[].ipAddresses.[].ipAddress')
  _info "Restored CloudSQL DB instance $restored_instance_id has IP $instance_ip"
  patch_deployment_postgres_ip "$component" "$instance_ip"
}

function restore_component() {
  local -r component=$1

  if [ "$component" == "cometbft" ]; then
    _info "Restoring cometbft"
    create_pvc_from_snapshot "cometbft-data-$run_id" "cometbft-data-$run_id-restore"
    use_restored_pvc_in_cometbft "cometbft-data-$run_id-restore"
  else
    _info "Restoring $component"
    type=$(get_postgres_type "$namespace-$component-pg")
    case "$type" in
      "canton:network:postgres")
        restore_pvc_postgres "$component"
        ;;
      "canton:cloud:postgres")
        restore_cloudsql_postgres "$component"
        ;;
      *)
        _error "Unknown postgres type: $type"
        ;;
    esac
  fi
}

function usage() {
  echo "Usage: $0 <namespace> <run_id> <component>..."
}

function main() {
  if [ "$#" -lt 3 ]; then
    usage
    exit 1
  fi

  readonly namespace=$1
  readonly run_id=$2

  for component in "${@:3}"; do
    if [[ ! -v component_to_deployment["$component"] ]]; then
      _error "Unknown component: $component"
    fi
  done

  _info " ** Scaling down ** "
  for component in "${@:3}"; do
    down "$component"
  done

  _info " ** Restoring ** "
  for component in "${@:3}"; do
    restore_component "$component"
  done

  _info " ** Scaling up ** "
  for component in "${@:3}"; do
    up "$component"
  done


}

main "$@"
