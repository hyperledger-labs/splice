{{- define "prefix" -}}
{{- $values := index . 0 -}}
{{- $suffix := index . 1 -}}
{{- printf "%s-%s" $values.node.identifier $suffix -}}
{{- end }}

{{- define "cliArgs" }}
--home /cometbft \
--log_level="*:debug" \
{{- end }}
