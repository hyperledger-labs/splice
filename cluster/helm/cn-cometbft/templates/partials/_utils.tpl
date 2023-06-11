{{- define "prefix" -}}
{{- $values := index . 0 -}}
{{- $suffix := index . 1 -}}
{{- printf "%s-%s" $values.svNodeId $suffix -}}
{{- end }}

{{- define "cliArgs" }}
--home /cometbft \
{{- end }}
