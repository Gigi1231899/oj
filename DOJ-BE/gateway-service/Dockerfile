# Stage 1: Build the application
# build阶段，maven负责下载依赖，编译代码，打包jar包
FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/maven:3.8.4-openjdk-17-slim AS build
# 工作目录
WORKDIR /app
# 都在/app下执行
# Copy all pom.xml files first to leverage Docker cache
COPY DOJ-BE/pom.xml .
COPY DOJ-BE/common/pom.xml ./common/
COPY DOJ-BE/gateway-service/pom.xml ./gateway-service/
COPY DOJ-BE/problem-service/pom.xml ./problem-service/
COPY DOJ-BE/sandbox-service/pom.xml ./sandbox-service/
COPY DOJ-BE/submission-service/pom.xml ./submission-service/
COPY DOJ-BE/user-service/pom.xml ./user-service/
# 缓存命中率高的先执行，放在前面
# Download all dependencies
# pom.xml文件中定义了项目的依赖关系，maven会根据这些依赖下载所需的jar包
# pom.xml没变不需要重新下载
RUN mvn dependency:go-offline

# Copy all source code
COPY DOJ-BE/common/src ./common/src
COPY DOJ-BE/gateway-service/src ./gateway-service/src

# Build the specific service
# 解析pom.xml文件，找到gateway-service模块，构建common模块和gateway-service模块，跳过测试
RUN mvn -f ./pom.xml -pl gateway-service -am clean package -DskipTests
RUN ls -al ./gateway-service/target

# Stage 2: Create the runtime image
FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/library/eclipse-temurin:17-jre-jammy

WORKDIR /app

# Copy the executable jar from the build stage
COPY --from=build /app/gateway-service/target/gateway-service.jar ./app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "./app.jar"]
