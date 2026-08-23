FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/mcr.microsoft.com/openjdk/jdk:25-ubuntu
USER root
RUN apt-get update && apt-get install -y time && apt-get clean
