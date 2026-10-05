{{/* Chart name */}}
{{- define "dbp.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Fully qualified release name */}}
{{- define "dbp.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/* Common labels */}}
{{- define "dbp.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | quote }}
app.kubernetes.io/part-of: dbp
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end -}}

{{/* Component selector labels: usage {{ include "dbp.selectorLabels" (dict "root" . "component" "gateway") }} */}}
{{- define "dbp.selectorLabels" -}}
app.kubernetes.io/name: {{ include "dbp.fullname" .root }}-{{ .component }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
dbp.io/component: {{ .component }}
{{- end -}}

{{/* Image reference: usage {{ include "dbp.image" (dict "root" . "repository" .Values.gateway.image) }} */}}
{{- define "dbp.image" -}}
{{- printf "%s%s:%s" .root.Values.image.registry .repository .root.Values.image.tag -}}
{{- end -}}

{{/* Name of the Secret holding tokens and passwords */}}
{{- define "dbp.secretName" -}}
{{- if .Values.secrets.existingSecret -}}
{{- .Values.secrets.existingSecret -}}
{{- else -}}
{{- include "dbp.fullname" . }}-secrets
{{- end -}}
{{- end -}}

{{/* Control plane in-cluster URL */}}
{{- define "dbp.controlPlaneUrl" -}}
{{- printf "http://%s-control-plane.%s.svc.cluster.local:%d" (include "dbp.fullname" .) .Release.Namespace (int .Values.controlPlane.service.port) -}}
{{- end -}}
