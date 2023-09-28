FROM clojure:temurin-20-tools-deps

WORKDIR /var/lib/monkeyci
ENTRYPOINT ["java", "-jar", "monkeyci.jar"]

RUN apt-get update && apt-get install -y podman

ENV MONKEYCI_CONTAINERS_TYPE=podman

ADD app/target/monkeyci-standalone.jar monkeyci.jar
