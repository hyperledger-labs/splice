{{- define "cn-util-lib.auth0-env-vars" -}}
{{- $app := .appName }}
{{- $keyName := .keyName }}
{{- $fixedTokens := .fixedTokens }}
- name: "CN_APP_{{ $app | upper }}_LEDGER_API_AUTH_USER_NAME"
  valueFrom:
    secretKeyRef:
      key: ledger-api-user
      name: "cn-app-{{ $keyName }}-ledger-api-auth"
      optional: false
{{- if .fixedTokens }}
- name: ADDITIONAL_CONFIG_AUTH
  value: |
    _client_credentials_auth_config = null
    _client_credentials_auth_config = {
      type = "static"
      token = ${CN_APP_{{ $app | upper }}_LEDGER_API_AUTH_TOKEN}
    }
- name: "CN_APP_{{ $app | upper }}_LEDGER_API_AUTH_TOKEN"
  valueFrom:
    secretKeyRef:
      key: token
      name: "cn-app-{{ $keyName }}-ledger-api-auth"
      optional: false
{{ else }}
- name: "CN_APP_{{ $app | upper }}_LEDGER_API_AUTH_URL"
  valueFrom:
    secretKeyRef:
      key: url
      name: "cn-app-{{ $keyName }}-ledger-api-auth"
      optional: false
- name: "CN_APP_{{ $app | upper }}_LEDGER_API_AUTH_CLIENT_ID"
  valueFrom:
    secretKeyRef:
      key: client-id
      name: "cn-app-{{ $keyName }}-ledger-api-auth"
      optional: false
- name: "CN_APP_{{ $app | upper }}_LEDGER_API_AUTH_CLIENT_SECRET"
  valueFrom:
    secretKeyRef:
      key: client-secret
      name: "cn-app-{{ $keyName }}-ledger-api-auth"
      optional: false
- name: "CN_APP_{{ $app | upper }}_LEDGER_API_AUTH_AUDIENCE"
  valueFrom:
    secretKeyRef:
      key: audience
      name: "cn-app-{{ $keyName }}-ledger-api-auth"
      optional: true
{{- end }}
{{- end -}}
{{- define "cn-util-lib.auth0-user-env-var" -}}
{{- $app := .appName }}
{{- $keyName := .keyName }}
- name: "CN_APP_{{ $app | upper }}_LEDGER_API_AUTH_USER_NAME"
  valueFrom:
    secretKeyRef:
      key: ledger-api-user
      name: "cn-app-{{ $keyName }}-ledger-api-auth"
      optional: false
{{- end -}}
