# syntax=docker/dockerfile:1
# Imagem da aplicação (ADR-0001, D15): build com o Maven Wrapper, runtime só com a JRE e usuário sem privilégios.
# Uso: docker build -t encurtador-url .

FROM eclipse-temurin:25.0.5_8-jdk-noble AS build
WORKDIR /build

# Dependências numa camada própria: mudar só o código não baixa tudo de novo.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

# Os testes precisam de Docker (Testcontainers) e rodam no CI com ./mvnw verify, não dentro do build da imagem.
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests \
    && cp target/encurtador-url-*.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:25.0.5_8-jre-noble

# curl só para o HEALTHCHECK: a imagem da JRE não traz curl nem wget.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app
# Camadas do Spring Boot, da que menos muda para a que mais muda.
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./

USER app:app
# 8080: API e redirecionamento. 8081: actuator completo, só para a rede interna (D13).
EXPOSE 8080 8081
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# /livez, e não /actuator/health: Redis e Kafka entram no health completo, e a queda deles não pode reiniciar a
# aplicação, que continua redirecionando sem eles (D13, RNF02).
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/livez > /dev/null || exit 1

ENTRYPOINT ["java", "-jar", "application.jar"]
