{{- define "splice-util-lib.secret" -}}
{{- $overrideValue := "" -}}
{{- if .overrides -}}
  {{- if hasKey .overrides .overrideKey -}}
    {{- $overrideValue = index .overrides .overrideKey -}}
  {{- end -}}
{{- end -}}
{{- if $overrideValue -}}
value: {{ $overrideValue | quote }}
{{- else -}}
valueFrom:
  secretKeyRef:
    key: {{ .secretKey }}
    name: {{ .secretName }}
    optional: {{ if hasKey . "optional" }}{{ .optional }}{{ else }}false{{ end }}
{{- end -}}
{{- end -}}
{{- define "splice-util-lib.auth0-env-vars" -}}
{{- $app := .appName }}
{{- $keyName := .keyName }}
{{- $fixedTokens := .fixedTokens }}
{{- $secretName := .secretName | default (printf "splice-app-%s-ledger-api-auth" $keyName) }}
- name: "SPLICE_APP_{{ $app | upper }}_LEDGER_API_AUTH_USER_NAME"
{{ include "splice-util-lib.secret" (dict "overrides" .secretOverrides "overrideKey" "ledgerApiUser" "secretName" $secretName "secretKey" "ledger-api-user" "optional" false) | indent 2 }}
- name: "SPLICE_APP_{{ $app | upper }}_LEDGER_API_AUTH_AUDIENCE"
{{ include "splice-util-lib.secret" (dict "overrides" .secretOverrides "overrideKey" "audience" "secretName" $secretName "secretKey" "audience" "optional" false) | indent 2 }}
{{- if .fixedTokens }}
- name: ADDITIONAL_CONFIG_AUTH
  value: |
    _client_credentials_auth_config = null
    _client_credentials_auth_config = {
      type = "static"
      token = ${SPLICE_APP_{{ $app | upper }}_LEDGER_API_AUTH_TOKEN}
    }
- name: "SPLICE_APP_{{ $app | upper }}_LEDGER_API_AUTH_TOKEN"
{{ include "splice-util-lib.secret" (dict "overrides" .secretOverrides "overrideKey" "token" "secretName" $secretName "secretKey" "token" "optional" false) | indent 2 }}
{{ else }}
- name: "SPLICE_APP_{{ $app | upper }}_LEDGER_API_AUTH_URL"
{{ include "splice-util-lib.secret" (dict "overrides" .secretOverrides "overrideKey" "url" "secretName" $secretName "secretKey" "url" "optional" false) | indent 2 }}
- name: "SPLICE_APP_{{ $app | upper }}_LEDGER_API_AUTH_CLIENT_ID"
{{ include "splice-util-lib.secret" (dict "overrides" .secretOverrides "overrideKey" "clientId" "secretName" $secretName "secretKey" "client-id" "optional" false) | indent 2 }}
- name: "SPLICE_APP_{{ $app | upper }}_LEDGER_API_AUTH_CLIENT_SECRET"
{{ include "splice-util-lib.secret" (dict "overrides" .secretOverrides "overrideKey" "clientSecret" "secretName" $secretName "secretKey" "client-secret" "optional" false) | indent 2 }}
- name: "SPLICE_APP_{{ $app | upper }}_LEDGER_API_AUTH_SCOPE"
{{ include "splice-util-lib.secret" (dict "overrides" .secretOverrides "overrideKey" "scope" "secretName" $secretName "secretKey" "scope" "optional" true) | indent 2 }}
{{- end }}
{{- end -}}
{{- define "splice-util-lib.auth0-user-env-var" -}}
{{- $app := .appName }}
{{- $keyName := .keyName }}
- name: "SPLICE_APP_{{ $app | upper }}_LEDGER_API_AUTH_USER_NAME"
{{ include "splice-util-lib.secret" (dict "overrides" .secretOverrides "overrideKey" "ledgerApiUser" "secretName" (printf "splice-app-%s-ledger-api-auth" $keyName) "secretKey" "ledger-api-user" "optional" false) | indent 2 }}
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
{{- $priorityClassName := .priorityClassName }}
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
      {{- include "splice-util-lib.service-account" . | nindent 6 }}
      volumes:
        - name: postgres-password
          secret:
            secretName: {{ $persistence.secretName }}
            items:
              - key: postgresPassword
                path: postgresPassword
      containers:
      - name: postgres-exporter
        image: quay.io/prometheuscommunity/postgres-exporter:v0.19.1
        env:
          - name: DATA_SOURCE_PASS_FILE
            value: /tmp/pwd
          - name: DATA_SOURCE_USER
            value: {{ $persistence.user | default "cnadmin" }}
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
{{ include "splice-util-lib.secret" (dict "overrides" $persistence.secretOverrides "overrideKey" "postgresPassword" "secretName" $persistence.secretName "secretKey" "postgresPassword") | indent 12 }}
        command:
          - 'bash'
          - '-c'
          - |
            until errmsg=$(psql -h {{ $persistence.host }} -p {{ $persistence.port }} --username={{ $persistence.user | default "cnadmin" }} --dbname={{ $persistence.databaseName }} -p {{ $persistence.port | default 5432 }} -c 'select 1' 2>&1); do
                echo "Waiting for database {{ $persistence.databaseName }}, at hostname {{ $persistence.host }}, port {{ $persistence.port | default 5432 }} to be accessible. Last error: $errmsg"
                sleep 2;
            done
      {{- with $priorityClassName }}
      priorityClassName: {{ . | quote }}
      {{- end }}
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
- name: LOG_LEVEL_API_REQUEST
  value: {{ .apiRequestLogLevel | default "DEBUG" }}
- name: LOG_LEVEL_STDOUT
  value: {{ .logLevelStdout | default "DEBUG" }}
- name: LOG_IMMEDIATE_FLUSH
  value: {{ .logAsyncFlush | default true | not | quote }}
{{- end }}
{{- define "splice-util-lib.service-account" -}}
automountServiceAccountToken: {{ .automountServiceAccountToken | default false }}
{{- if .serviceAccountName }}
serviceAccountName: {{ .serviceAccountName }}
{{- end }}
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
{{- define "splice-util-lib.default-annotations" -}}
{{- if (.enableReloader | default false) -}}
reloader.stakater.com/auto: "true"
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
