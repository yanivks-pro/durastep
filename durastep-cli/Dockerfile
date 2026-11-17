FROM eclipse-temurin:25.0.5_8-jre
COPY target/durastep-cli-*.jar /opt/durastep/durastep-cli.jar
USER 65534
ENTRYPOINT ["java", "-jar", "/opt/durastep/durastep-cli.jar"]
