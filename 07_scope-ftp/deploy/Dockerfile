# 构建阶段：Maven + JDK 21
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 国内服务器构建时走阿里云镜像（Docker 构建上下文为项目根目录 07_scope-ftp/）
COPY deploy/docker-maven-settings.xml /root/.m2/settings.xml

# 先只拷贝 pom，利用 Docker 层缓存预拉依赖
COPY server/pom.xml ./pom.xml
RUN mvn -B -q -DskipTests dependency:go-offline || true

# 再拷贝源码并打包
COPY server/src ./src
RUN mvn -B -DskipTests clean package && \
    cp target/*.jar /build/app.jar

# 运行阶段：仅 JRE 21
FROM eclipse-temurin:21-jre

LABEL org.opencontainers.image.title="scop-ftp-server" \
      org.opencontainers.image.description="ScopeFTP-server（Spring Boot / HMAC 请求签名）"

RUN groupadd -r app && useradd -r -g app -u 1001 app

WORKDIR /app
COPY --from=build /build/app.jar /app/app.jar
RUN mkdir -p /app/data /app/logs && chown -R app:app /app

USER app
EXPOSE 8080
VOLUME ["/app/data", "/app/logs"]

# JAVA_OPTS 由 docker-compose / 运行时注入
ENV JAVA_OPTS="-Xms256m -Xmx512m"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
