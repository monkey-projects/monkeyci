FROM docker.io/clojure:temurin-23-tools-deps-bullseye-slim

# We'll run the whole thing as non-root user
RUN adduser --system --uid 1000 --shell /bin/bash --disabled-password monkeyci

USER monkeyci

WORKDIR /home/monkeyci
# Config dir, can be used to specify logback config
VOLUME /home/monkeyci/config
# Override default memory limit for sidecar when smaller available memory
ENTRYPOINT ["java", "-Xmx1g", "-Dlogback.configurationFile=config/logback.xml", "--sun-misc-unsafe-memory-access=allow", "-cp", "monkeyci.jar", "monkey.ci.internal"]

COPY app/target/monkeyci-standalone.jar monkeyci.jar
