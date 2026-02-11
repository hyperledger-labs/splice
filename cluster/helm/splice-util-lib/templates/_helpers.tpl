{{- define "splice-util-lib.auth0-env-vars" -}}
{{- $app := .appName }}
{{- $keyName := .keyName }}
{{- $fixedTokens := .fixedTokens }}
- name: "SPLICE_APP_{{ $app | upper }}_LEDGER_API_AUTH_USER_NAME"
  valueFrom:
    secretKeyRef:
      key: ledger-api-user
      name: "splice-app-{{ $keyName }}-ledger-api-auth"
      optional: false
- name: "SPLICE_APP_{{ $app | upper }}_LEDGER_API_AUTH_AUDIENCE"
  valueFrom:
    secretKeyRef:
      key: audience
      name: "splice-app-{{ $keyName }}-ledger-api-auth"
      optional: false
{{- if .fixedTokens }}
- name: ADDITIONAL_CONFIG_AUTH
  value: |
    _client_credentials_auth_config = null
    _client_credentials_auth_config = {
      type = "static"
      token = ${SPLICE_APP_{{ $app | upper }}_LEDGER_API_AUTH_TOKEN}
    }
- name: "SPLICE_APP_{{ $app | upper }}_LEDGER_API_AUTH_TOKEN"
  valueFrom:
    secretKeyRef:
      key: token
      name: "splice-app-{{ $keyName }}-ledger-api-auth"
      optional: false
{{ else }}
- name: "SPLICE_APP_{{ $app | upper }}_LEDGER_API_AUTH_URL"
  valueFrom:
    secretKeyRef:
      key: url
      name: "splice-app-{{ $keyName }}-ledger-api-auth"
      optional: false
- name: "SPLICE_APP_{{ $app | upper }}_LEDGER_API_AUTH_CLIENT_ID"
  valueFrom:
    secretKeyRef:
      key: client-id
      name: "splice-app-{{ $keyName }}-ledger-api-auth"
      optional: false
- name: "SPLICE_APP_{{ $app | upper }}_LEDGER_API_AUTH_CLIENT_SECRET"
  valueFrom:
    secretKeyRef:
      key: client-secret
      name: "splice-app-{{ $keyName }}-ledger-api-auth"
      optional: false
- name: "SPLICE_APP_{{ $app | upper }}_LEDGER_API_AUTH_SCOPE"
  valueFrom:
    secretKeyRef:
      key: scope
      name: "splice-app-{{ $keyName }}-ledger-api-auth"
      optional: true
{{- end }}
{{- end -}}
{{- define "splice-util-lib.auth0-user-env-var" -}}
{{- $app := .appName }}
{{- $keyName := .keyName }}
- name: "SPLICE_APP_{{ $app | upper }}_LEDGER_API_AUTH_USER_NAME"
  valueFrom:
    secretKeyRef:
      key: ledger-api-user
      name: "splice-app-{{ $keyName }}-ledger-api-auth"
      optional: false
{{- end -}}
{{- define "splice-util-lib.additional-env-vars" -}}
{{- range $var := . }}
- name: {{ $var.name }}
  {{- if $var.valueFrom }}
  valueFrom: {{ toYaml $var.valueFrom | nindent 4 }}
  {{- else if $var.value }}
  value: {{ $var.value | quote }}
  {{- end }}
{{- end }}
{{- end }}
{{- define "splice-util-lib.postgres-metrics" -}}
{{- $name := print "pge-" .persistence.postgresName "-" (.persistence.databaseName | replace "_" "-" ) }}
{{- $namespace := .namespace }}
{{- $persistence := .persistence }}
{{- $nodeSelector := .nodeSelector }}
{{- $affinity := .affinity }}
{{- $tolerations := .tolerations }}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ $name }}
  namespace: {{ $namespace }}
  labels:
    app: {{ $name }}
spec:
  replicas: 1
  selector:
    matchLabels:
      app: {{ $name }}
  template:
    metadata:
      labels:
        app: {{ $name }}
    spec:
      {{- include "splice-util-lib.service-account" .Values | nindent 6 }}
      volumes:
        - name: postgres-password
          secret:
            secretName: {{ $persistence.secretName }}
            items:
              - key: postgresPassword
                path: postgresPassword
      containers:
      - name: postgres-exporter
        image: quay.io/prometheuscommunity/postgres-exporter:v0.19.0
        env:
          - name: DATA_SOURCE_PASS_FILE
            value: /tmp/pwd
          - name: DATA_SOURCE_USER
            value: cnadmin
          - name: DATA_SOURCE_URI
            value: {{ $persistence.host  }}:{{ $persistence.port | default 5432 }}/{{ $.persistence.databaseName }}?sslmode=disable
        command:
          - '/bin/postgres_exporter'
          - '--log.format=json'
        volumeMounts:
          - name: postgres-password
            mountPath: "/tmp/pwd"
            subPath: postgresPassword
            readOnly: true
      initContainers:
      # If postgres is not yet ready, postgres-exporter fails when initializing the collector
      # but never retries nor indicates non-readiness, so we unfortunately need to wait for it
      # to be ready before spinning up postgres-exporter.
      - name: postgres-exporter-init
        image: postgres:14
        env:
          - name: PGPASSWORD
            valueFrom:
              secretKeyRef:
                key: postgresPassword
                name: {{ $persistence.secretName }}
        command:
          - 'bash'
          - '-c'
          - |
            until errmsg=$(psql -h {{ $persistence.host }} -p {{ $persistence.port }} --username=cnadmin --dbname={{ $persistence.databaseName }} -p {{ $persistence.port | default 5432 }} -c 'select 1' 2>&1); do
                echo "Waiting for database {{ $persistence.databaseName }}, at hostname {{ $persistence.host }}, port {{ $persistence.port | default 5432 }} to be accessible. Last error: $errmsg"
                sleep 2;
            done
      {{- with $nodeSelector }}
      nodeSelector:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with $affinity }}
      affinity:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with $tolerations }}
      tolerations:
        {{- toYaml . | nindent 8 }}
      {{- end }}
---
apiVersion: v1
kind: Service
metadata:
  name: {{ $name }}
  namespace: {{ $namespace }}
  labels:
    app: {{ $name }}
    server: {{ $persistence.host }}
spec:
  ports:
  - name: postgres-metrics
    port: 9187
    protocol: TCP
  selector:
    app: {{ $name }}
---
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  labels:
    release: prometheus-grafana-monitoring
  name: {{ $name }}
  namespace: {{ $namespace }}
spec:
  endpoints:

  - port: postgres-metrics
    interval: 30s

  selector:
    matchLabels:
      app: {{ $name }}

  namespaceSelector:
    matchNames:
    - {{ $namespace }}

  targetLabels:
      - server
{{- end }}
{{- define "splice-util-lib.log-level" }}
- name: LOG_LEVEL_CANTON
  value: {{ .logLevel | default "INFO" }}
- name: LOG_LEVEL_STDOUT
  value: {{ .logLevelStdout | default "DEBUG" }}
- name: LOG_IMMEDIATE_FLUSH
  value: {{ .logAsyncFlush | default true | not | quote }}
{{- end }}
{{- define "splice-util-lib.service-account" -}}
{{- if .serviceAccountName -}}
serviceAccountName: {{ .serviceAccountName }}
{{- end -}}
{{- end -}}
# See https://helm.sh/docs/chart_best_practices/labels/#standard-labels
{{- define "splice-util-lib.standard-labels" -}}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/name: {{ .app }}
app.kubernetes.io/version: {{ .Chart.AppVersion }}
helm.sh/chart: {{ trunc 63 (printf "%s-%s" .Chart.Name ( .Chart.Version | replace "+" "_" )) }}
{{- end -}}
{{- define "splice-util-lib.default-labels" -}}
app: {{ .app }}
{{ include "splice-util-lib.standard-labels" . }}
{{- with .Values.pod }}
{{- with .labels }}
{{ toYaml . }}
{{- end }}
{{- end }}
{{- end -}}
{{- define "splice-util-lib.render-nested-config" }}
{{- range $key, $value := . }}
{{ $key | kebabcase }} = {{- if kindIs "map" $value }} {
{{- include "splice-util-lib.render-nested-config" $value | trim | nindent 2 }}
}
{{- else }} {{ $value }}
{{- end }}
{{- end -}}
{{- end -}}

{{- define "splice-util-lib.affinity" -}}
{{- if or .enableAntiAffinity .affinity }}
affinity:
  {{- with .affinity }}
    {{- toYaml . | nindent 2 }}
  {{- end }}
  {{- if .enableAntiAffinity }}
  podAntiAffinity:
    requiredDuringSchedulingIgnoredDuringExecution:
      - labelSelector:
          matchExpressions:
            - key: splice-component
              operator: In
              values:
                - {{ .app }}
        topologyKey: kubernetes.io/hostname
        namespaceSelector: { } # search in all namespaces
  {{- end }}
{{- end }}
{{- end -}}
