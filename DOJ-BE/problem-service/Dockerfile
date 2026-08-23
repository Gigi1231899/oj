# Stage 1: Build the application
FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/maven:3.8.4-openjdk-17-slim AS build
# 工作目录
WORKDIR /app

# Copy all pom.xml files first to leverage Docker cache
COPY DOJ-BE/pom.xml .
COPY DOJ-BE/common/pom.xml ./common/
COPY DOJ-BE/gateway-service/pom.xml ./gateway-service/
COPY DOJ-BE/problem-service/pom.xml ./problem-service/
COPY DOJ-BE/sandbox-service/pom.xml ./sandbox-service/
COPY DOJ-BE/submission-service/pom.xml ./submission-service/
COPY DOJ-BE/user-service/pom.xml ./user-service/

# Download all dependencies
RUN mvn dependency:go-offline

# Copy all source code
COPY DOJ-BE/common/src ./common/src
COPY DOJ-BE/problem-service/src ./problem-service/src

# Build the specific service
RUN mvn -f ./pom.xml -pl problem-service -am clean package -DskipTests
RUN ls -al ./problem-service/target

# Stage 2: Create the runtime image
FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/library/eclipse-temurin:17-jre-jammy

WORKDIR /app

# Copy the executable jar from the build stage
COPY --from=build /app/problem-service/target/problem-service.jar ./app.jar

EXPOSE 8083

ENTRYPOINT ["java", "-jar", "./app.jar"]
