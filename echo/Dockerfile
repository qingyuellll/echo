# syntax=docker/dockerfile:1
# ============================================================
# 回响 Echo - 后端镜像（多阶段构建）
#   阶段1 maven 编译（容器内完成，不依赖宿主机 JDK/Maven）
#   阶段2 只带 JRE + 可执行 jar
# ============================================================

FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build

# 阿里云 Maven 镜像，避免拉中央仓库超时
COPY docker/maven-settings.xml /root/.m2/settings.xml

COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -q dependency:go-offline -Dmaven.test.skip=true || true

COPY src ./src
# 跳过测试与测试编译（pom 里 test-compile 绑了 lombok 处理器，跳过更稳）
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B clean package -Dmaven.test.skip=true \
 && cp target/echo-0.0.1-SNAPSHOT.jar /build/app.jar

# ------------------------------------------------------------
FROM eclipse-temurin:17-jre

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

WORKDIR /app
COPY --from=builder /build/app.jar /app/app.jar

# 图片上传根目录（对应 echo.upload.image-root），由 compose 挂载卷
RUN mkdir -p /data/imgs
VOLUME ["/data/imgs"]

EXPOSE 8081

HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=8 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8081' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Duser.timezone=Asia/Shanghai -jar /app/app.jar"]
