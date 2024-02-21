FROM docker.io/clojure:temurin-21-tools-deps-jammy

# We'll run the whole thing as non-root user
RUN adduser --uid 1000 --shell /bin/bash monkeyci

USER monkeyci

WORKDIR /home/monkeyci
# Config dir, can be used to specify logback config
VOLUME /home/monkeyci/config
ENTRYPOINT ["java", "-Dlogback.configurationFile=config/logback.xml", "-jar", "monkeyci.jar"]

VOLUME /home/monkeyci/work

ENV MONKEYCI_WORK_DIR=/home/monkeyci/work

ADD app/target/monkeyci-standalone.jar monkeyci.jar
