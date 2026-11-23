# ---- 构建阶段：Maven + JDK 17 ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# 先拷 pom 拉依赖，形成可缓存的依赖层
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# ---- 运行阶段：JRE + 中文字体（路书 PDF 需要）+ curl（healthcheck）----
FROM eclipse-temurin:17-jre
# fonts-noto-cjk 供 TripGuidebookService 生成中文 PDF；默认探测路径已覆盖 Noto CJK
# tzdata + TZ：**必须**把容器时区钉成业务时区（下方 ENV TZ）。原因（实测）：
#   TimeZoneConfig 在 Spring 启动后才 setDefault，而 JDBC 会话在建连时就用掉了启动时区；
#   容器默认 UTC 会让 LocalDate 整体偏一天（行程 2026-11-20 读回 2026-11-19、
#   携程比价链接日期同样偏移、提醒窗口错位）。CI 上同一现象已通过 surefire 钉
#   -Duser.timezone 修掉，这里修运行时。
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl fonts-noto-cjk tzdata \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /build/target/travel-agent-server-0.1.0.jar app.jar

# 数据卷：H2 文件库 / 行程照片（compose 挂卷持久化）
RUN mkdir -p /app/data && chown -R 1000:1000 /app
USER 1000
VOLUME ["/app/data"]

ENV TZ=Asia/Shanghai \
    APP_TIMEZONE=Asia/Shanghai \
    JAVA_OPTS=""
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD ["curl", "-fsS", "http://localhost:8080/actuator/health"] || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
