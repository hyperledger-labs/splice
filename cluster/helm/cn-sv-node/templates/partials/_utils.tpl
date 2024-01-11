{{- define "domainOrDefaultValue" -}}
{{- $values := index . 0 -}}
{{- $name := index . 1 -}}
{{- if $values.domainId }}
{{- printf "%s-%s" $name $values.domainId }}
{{- else }}
{{- $name }}
{{- end }}
{{- end }}
