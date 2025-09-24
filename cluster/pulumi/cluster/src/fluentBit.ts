// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import {
  appsAffinityAndTolerations,
  CLUSTER_NAME,
  exactNamespace,
  GCP_REGION,
  infraAffinityAndTolerations,
} from '@lfdecentralizedtrust/splice-pulumi-common';

export function installFluentBit(): void {
  // GCP does not allow configuring the fluent-bit instance it deploys. So we disable GCP's fluent-bit instance for workloads and install our own that
  const fluentBit = exactNamespace('fluent-bit');

  const values = {
    tolerations: infraAffinityAndTolerations.tolerations.concat(
      appsAffinityAndTolerations.tolerations
    ),
    config: {
      inputs: [
        // Input config is roughly copied from the default GCP config available from `kubectl get configmap -n kube-system fluentbit-gke-config-v1.4.0 -o yaml`
        '[Input]',
        '    Name              tail',
        '    Path              /var/log/containers/*.log',
        '    Exclude_Path     /var/log/containers/*_kube-system_*.log,/var/log/containers/*_istio-system_*.log,/var/log/containers/*_knative-serving_*.log,/var/log/containers/*_gke-system_*.log,/var/log/containers/*_config-management-system_*.log,/var/log/containers/*_gmp-system_*.log,/var/log/containers/*_gke-managed-cim_*.log,/var/log/containers/*_gke-managed-volumepopulator_*.log,/var/log/containers/*_gke-managed-checkpointing_*.log,/var/log/containers/*_gke-managed-lustrecsi_*.log',
        '    DB                /var/log/flb_kube.db',
        '    Mem_Buf_Limit     100MB',
        '    Skip_Long_Lines   On',
        '    Refresh_Interval  1',
        '    Buffer_Max_Size   20MB',
        '    Read_from_Head True',
        '    Tag kube.*',
      ].join('\n'),
      outputs: [
        // Stack driver
        '[Output]',
        '    Name stackdriver',
        '    Match kube.*',
        '    Tag_Prefix  kube.var.log.containers.',
        // This ensures that non-JSON fields get parsed as textPayload
        '    text_payload_key message',
        // This ensures that the k8s labels added by the filter get propagated to the labels field in log explorer
        '    labels_key k8s_labels',
        '    resource k8s_container',
        // Mandatory parameters, doesn't look like we have a way to exclude the location sadly even though it isn't very useful for us.
        `    k8s_cluster_name ${CLUSTER_NAME}`,
        `    k8s_cluster_location  ${GCP_REGION}`,
        `    severity_key level`,
      ].join('\n'),
      filters: [
        // First parse just in containerd format to extra time stamps and friends
        '[FILTER]',
        '    Name         parser',
        '    Match        kube.*',
        '    Key_Name     log',
        '    Reserve_Data True',
        '    Parser       containerd',
        // Rename log to message as this is what stack driver expects
        '[FILTER]',
        '    Name        modify',
        '    Match       *',
        '    Hard_rename log message',
        // First parse just in containerd format to extra time stamps and friends
        '[FILTER]',
        '    Name         lua',
        '    Match        kube.*',
        '    script  /fluent-bit/scripts/k8s_filters.lua',
        '    call    truncate',
        // Try to parse as glog and json, that matches what the default gc configuration does"
        '[FILTER]',
        '    Name         parser',
        '    Match        kube.*',
        '    Key_Name     message',
        '    Reserve_Data True',
        '    Parser       fluentbit',
        '    Parser       glog',
        '    Parser       json_iso8601',
        // Enrich with k8s metadata
        '[FILTER]',
        '    Name             kubernetes',
        '    Buffer_Size      1Mb',
        '    Match            kube.*',
        '    Kube_URL         https://kubernetes.default.svc:443',
        '    Kube_CA_File     /var/run/secrets/kubernetes.io/serviceaccount/ca.crt',
        '    Kube_Token_File  /var/run/secrets/kubernetes.io/serviceaccount/token',
        '    Kube_Tag_Prefix  kube.var.log.containers.',
        '    Merge_Log        Off',
        '    Merge_Log_Key    log_processed',
        '    Annotations      Off',
        // Tweak the k8s labels to only keep the ones we care about
        '[FILTER]',
        '    Name    lua',
        '    Match   kube.*',
        '    script  /fluent-bit/scripts/k8s_filters.lua',
        '    call    tweak_k8s_labels',
      ].join('\n'),
      customParsers: [
        // Parsers copied from the default GCP configuration. Note that fluentbit has a builtin cri parser but it behaves slightly different than the containerd parser here so for now we keep the custom containerd parser
        '[PARSER]',
        '    Name        containerd',
        '    Format      regex',
        '    # The timestamp is described in https://www.rfc-editor.org/rfc/rfc3339#section-5.6',
        '    Regex       ^(?<time>[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt ][0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]+)?(?:[Zz]|[+-][0-9]{2}:[0-9]{2})) (?<stream>stdout|stderr) [^ ]* (?<log>.*)$',
        '    Time_Key    time',
        '    Time_Format %Y-%m-%dT%H:%M:%S.%L%z',
        '[PARSER]',
        '    Name        glog',
        '    Format      regex',
        "    # We skip the glog timestamp as it doesn't have year (in most cases) and time zone information.",
        '    # Instead, we rely on the containerd timestamp, which is available for every log line.',
        '    Regex       ^(?<level>\\w)\\d{4}?\\d{4} [^\\s]*\\s+(?<pid>\\d+)\\s+(?<source_file>[^ \\]]+)\\:(?<source_line>\\d+)\\]\\s(?<message>.*)$',
        // yes fluentbit really does not have a parser for its log format
        '[PARSER]',
        '    Name        fluentbit',
        '    Format      regex',
        '    Regex  ^\\[(?<time>[^\\]]+)\\][ ]*\\[[ ]*(?<level>[^\\]]+)\\][ ]*\\[(?<plugin>[^\\]]+)\\] (?<message>.*)',
        '    Time_Key time',
        '    Time_Format %Y/%m/%d %H:%M:%S',
        '[PARSER]',
        '    Name        json_iso8601',
        '    Format json',
        '    Time_Key @timestamp',
        '    Time_Format %Y-%m-%dT%H:%M:%S.%L%z',
        // Allow missing second fraction,
        '    Time_Strict off',
      ].join('\n'),
    },
    luaScripts: {
      'k8s_filters.lua': [
        'function tweak_k8s_labels(tag, timestamp, record)',
        '  if record.kubernetes and record.kubernetes.labels then',
        '    record.k8s_labels = { ["app.kubernetes.io/name"] = record.kubernetes.labels["app.kubernetes.io/name"], ["app.kubernetes.io/version"] = record.kubernetes.labels["app.kubernetes.io/version"], ["migration_id"] = record.kubernetes.labels["migration_id"] }',
        '    record.kubernetes = nil',
        '  end',
        // 2 means we tweaked the record but not the timestamp
        '  return 2, timestamp, record',
        'end',
        // GCP does not like log messages more than 250k so we truncate to 200k here which seems to match what the builtin
        // parser does. Note that just like the upstream parser this does also break json parsing.
        'function truncate(tag, timestamp, record)',
        '  local max_length = 200000',
        '  if record.message and string.len(record.message) > max_length then',
        '    record.message = string.sub(record.message, 1, max_length)',
        '  end',
        '  return 2, timestamp, record',
        'end',
      ].join('\n'),
    },
  };

  new k8s.helm.v3.Release('fluent-bit', {
    name: 'fluent-bit',
    chart: 'fluent-bit',
    version: '0.51.0',
    repositoryOpts: {
      repo: 'https://fluent.github.io/helm-charts',
    },
    namespace: fluentBit.ns.metadata.name,
    values,
  });
}
