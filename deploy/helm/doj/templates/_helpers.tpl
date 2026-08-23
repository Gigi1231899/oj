{{/*
  ═══════════════════════════════════════════════════════════════
  D-OnlineJudge — Helm 公共宏 (_helpers.tpl)
  ═══════════════════════════════════════════════════════════════
  包含：镜像地址拼接、标签、环境变量注入等复用宏
  ═══════════════════════════════════════════════════════════════
*/}}

{{/* 公共标签 */}}
{{- define "doj.labels" }}
app.kubernetes.io/name: doj
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Values.global.imageTag }}
app.kubernetes.io/managed-by: helm
{{- end }}

{{/* Java 服务镜像地址：<imageRegistry>/<服务名>:<tag> */}}
{{- define "doj.java-image" }}
{{ .Values.global.imageRegistry }}/{{ .image }}:{{ .Values.global.imageTag }}
{{- end }}

{{/* 通用中间件环境变量 */}}
{{- define "doj.middleware-env" }}
- name: DOJ_DB_HOST
  value: {{ .Values.global.mysql.writeHost | quote }}
- name: DOJ_DB_PORT
  value: {{ .Values.global.mysql.port | quote }}
- name: DOJ_DB_USER
  value: {{ .Values.global.mysql.user | quote }}
- name: DOJ_DB_PASSWORD
  valueFrom:
    secretKeyRef:
      name: doj-secrets
      key: mysql-root-password
- name: DOJ_REDIS_SENTINEL_NODES
  value: {{ .Values.global.redisSentinelNodes | quote }}
- name: DOJ_REDIS_MASTER_NAME
  value: {{ .Values.global.redisMasterName | quote }}
- name: DOJ_RABBITMQ_ADDRESSES
  value: {{ .Values.global.rabbitmqAddresses | quote }}
- name: DOJ_RABBITMQ_USERNAME
  value: {{ .Values.global.rabbitmqUser | quote }}
- name: DOJ_RABBITMQ_PASSWORD
  valueFrom:
    secretKeyRef:
      name: doj-secrets
      key: rabbitmq-password
- name: DOJ_ELASTICSEARCH_URIS
  value: {{ .Values.global.elasticsearchUris | quote }}
- name: DOJ_NACOS_SERVER_ADDR
  value: {{ .Values.global.nacosServer | quote }}
{{- end }}
