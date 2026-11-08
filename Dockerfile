# syntax=docker/dockerfile:1
# Imagem de uma aplicação do repositório (ADR-0001, D15; ADR-0002, D9; ADR-0005, D7): build com o Maven Wrapper,
# runtime só com a JRE e usuário sem privilégios. MODULE é o caminho do módulo da aplicação.
# Uso: docker build -t encurtador-api .                                 (MODULE=apps/api, o padrão)
#      docker build --build-arg MODULE=<caminho do módulo> -t <imagem> .   (apps/redirect, apps/bff)

FROM eclipse-temurin:25.0.5_8-jdk-noble AS build
ARG MODULE=apps/api
WORKDIR /build

# O Node que o frontend-maven-plugin instala para o painel depende da libatomic.
RUN apt-get update \
    && apt-get install -y --no-install-recommends libatomic1 \
    && rm -rf /var/lib/apt/lists/*

# Dependências numa camada própria: mudar só o código não baixa tudo de novo. Os poms de todos os módulos entram
# antes das fontes, nas mesmas pastas; todo módulo fica a dois níveis da raiz (apps/api, platform/shared...).
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY --parents */*/pom.xml ./
# As bibliotecas de segurança vêm do GitHub Packages, que exige token (ADR-0007, D7): GITHUB_ACTOR e GITHUB_TOKEN
# chegam como secrets do BuildKit, só durante o RUN, e não ficam em nenhuma camada da imagem.
#   docker build --secret id=github_actor,env=GITHUB_ACTOR --secret id=github_token,env=GITHUB_TOKEN ...
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=secret,id=github_actor,env=GITHUB_ACTOR \
    --mount=type=secret,id=github_token,env=GITHUB_TOKEN \
    ./mvnw -B -q dependency:go-offline

# Os testes precisam de Docker (Testcontainers) e rodam no CI com ./mvnw verify, não dentro do build da imagem.
# O painel (frontend/web) vai inteiro: package.json, lock, angular.json, tsconfig, ESLint e public/, além do src/
# (ADR-0004, D12). O Node fica no repositório do Maven e os pacotes no cache do npm, os dois em cache entre builds.
COPY --parents */*/src ./
COPY frontend/web/ frontend/web/
RUN --mount=type=cache,target=/root/.m2 --mount=type=cache,target=/root/.npm \
    --mount=type=secret,id=github_actor,env=GITHUB_ACTOR \
    --mount=type=secret,id=github_token,env=GITHUB_TOKEN \
    ./mvnw -B -q package -DskipTests -pl ${MODULE} -am \
    && cp ${MODULE}/target/*-exec.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:25.0.5_8-jre-noble
# Portas da aplicação: HTTP (também a do HEALTHCHECK) e de gestão.
ARG HTTP_PORT=8080
ARG MANAGEMENT_PORT=8081

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
# Na API, 8080: rotas da API; 8081: actuator completo, só para a rede interna (D13). No redirect, 8060 e 8061
# (ADR-0006, D1); no BFF, 8070 e 8071.
EXPOSE ${HTTP_PORT} ${MANAGEMENT_PORT}
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    HEALTHCHECK_PORT=${HTTP_PORT}

# /livez, e não /actuator/health: Redis e Kafka entram no health completo, e a queda deles não pode reiniciar a
# aplicação, que continua funcionando sem eles (D13, RNF02).
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=3 \
    CMD curl -fsS "http://localhost:${HEALTHCHECK_PORT}/livez" > /dev/null || exit 1

ENTRYPOINT ["java", "-jar", "application.jar"]
