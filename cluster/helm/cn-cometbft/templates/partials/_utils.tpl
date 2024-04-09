{{- define "prefix" -}}
{{- $values := index . 0 -}}
{{- $suffix := index . 1 -}}
{{- printf "%s-%s" $values.node.identifier $suffix -}}
{{- end }}

{{- define "cliArgs" }}
{{- $values := index . 0 -}}
--home /cometbft \
--log_level="{{- if $values.extraLogLevelFlags }}{{- $values.extraLogLevelFlags }},{{- end }}pex:debug,CantonNetworkApplication:debug,*:info" \
{{- end }}

{{- define "isTestNet" -}}
{{- mustRegexMatch "^(test|cidaily-testnet)$" .Values.genesis.chainId -}}
{{- end }}
