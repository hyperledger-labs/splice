{{- define "cn-util-lib.auth0-env-vars" -}}
{{- $app := .appName }}
{{- $fixedTokens := .fixedTokens }}
{{ if .fixedTokens }}
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
      name: "cn-app-{{ $app }}-ledger-api-auth"
      optional: false
{{ else }}
- name: "CN_APP_{{ $app | upper }}_LEDGER_API_AUTH_URL"
  valueFrom:
    secretKeyRef:
      key: url
      name: "cn-app-{{ $app }}-ledger-api-auth"
      optional: false
- name: "CN_APP_{{ $app | upper }}_LEDGER_API_AUTH_CLIENT_ID"
  valueFrom:
    secretKeyRef:
      key: client-id
      name: "cn-app-{{ $app }}-ledger-api-auth"
      optional: false
- name: "CN_APP_{{ $app | upper }}_LEDGER_API_AUTH_CLIENT_SECRET"
  valueFrom:
    secretKeyRef:
      key: client-secret
      name: "cn-app-{{ $app }}-ledger-api-auth"
      optional: false
{{ end }}
- name: "CN_APP_{{ $app | upper }}_LEDGER_API_AUTH_USER_NAME"
  valueFrom:
    secretKeyRef:
      key: ledger-api-user
      name: "cn-app-{{ $app }}-ledger-api-auth"
      optional: false
{{- end -}}
