{{- define "domainMigrationIdOrDefaultValue" -}}
{{- $values := index . 0 -}}
{{- $name := index . 1 -}}
{{- if $values.domainMigrationId }}
{{- printf "%s-%s" $name $values.domainMigrationId }}
{{- else }}
{{- $name }}
{{- end }}
{{- end }}
