{{- define "orbit.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "orbit.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s" (include "orbit.name" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "orbit.labels" -}}
app.kubernetes.io/name: {{ include "orbit.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
{{- end -}}

{{/* Per-component selector labels. Call: (dict "root" $ "component" "backend") */}}
{{- define "orbit.selectorLabels" -}}
app.kubernetes.io/name: {{ include "orbit.name" .root }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end -}}

{{- define "orbit.secretName" -}}
{{ include "orbit.fullname" . }}-secrets
{{- end -}}

{{/* The JDBC URL the backend uses: in-cluster postgres service or the external one. */}}
{{- define "orbit.dbUrl" -}}
{{- if .Values.postgres.enabled -}}
jdbc:postgresql://{{ include "orbit.fullname" . }}-postgres:5432/{{ .Values.postgres.db }}
{{- else -}}
{{ .Values.postgres.external.url }}
{{- end -}}
{{- end -}}

{{- define "orbit.dbUser" -}}
{{- if .Values.postgres.enabled -}}{{ .Values.postgres.user }}{{- else -}}{{ .Values.postgres.external.user }}{{- end -}}
{{- end -}}
