FROM eclipse-temurin:20-jre

WORKDIR /var/lib/monkeyci
ENTRYPOINT ["java", "-jar", "monkeyci.jar"]

RUN apt-get update && apt-get install -y podman

ADD app/target/monkeyci-standalone.jar monkeyci.jar
