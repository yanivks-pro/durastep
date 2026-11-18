FROM eclipse-temurin:25.0.5_8-jdk AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -q package -pl durastep-examples,durastep-cli -am \
      -Dmaven.test.skip=true -Djacoco.skip=true -Dspotless.check.skip=true

FROM eclipse-temurin:25.0.5_8-jre
COPY --from=build /src/durastep-examples/target/durastep-demo.jar /opt/durastep/durastep-demo.jar
COPY --from=build /src/durastep-cli/target/durastep-cli-*.jar /opt/durastep/durastep-cli.jar
USER 65534
ENTRYPOINT ["java", "-jar", "/opt/durastep/durastep-demo.jar"]
