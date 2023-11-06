FROM docker.io/clojure:temurin-21-tools-deps

WORKDIR /var/lib/monkeyci
ENTRYPOINT ["java", "-jar", "monkeyci.jar"]

# Install podman.  When building multi-platform images, this will only
# work if qemu is installed
RUN apt-get update && apt-get install -y podman

ENV MONKEYCI_CONTAINERS_TYPE=podman

ADD docker/subuid /etc
ADD docker/subgid /etc
ADD app/target/monkeyci-standalone.jar monkeyci.jar
