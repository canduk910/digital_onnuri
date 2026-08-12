# ── 빌드 단계 ── Gradle 8.10 + JDK 21 (프로젝트 wrapper 버전과 동일)
FROM gradle:8.10-jdk21 AS build
WORKDIR /workspace
# 의존성 캐시 최적화: 빌드 스크립트 먼저 복사
COPY build.gradle settings.gradle ./
RUN gradle dependencies --no-daemon || true
# 소스 복사 후 실행 가능 jar 생성(테스트는 CI에서 별도 수행)
COPY src ./src
RUN gradle bootJar --no-daemon -x test

# ── 런타임 단계 ── JRE 21 슬림. 비루트 실행.
FROM eclipse-temurin:21-jre-jammy
# 컨테이너 헬스체크용 curl
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
RUN useradd -r -u 1001 -g root onnuri
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
USER 1001
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Duser.timezone=Asia/Seoul"
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar app.jar"]
