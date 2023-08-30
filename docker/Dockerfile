FROM eclipse-temurin:20-jre

WORKDIR /var/lib/monkeyci
ENTRYPOINT ["java", "-jar", "monkeyci.jar"]

ADD app/target/monkeyci-standalone.jar monkeyci.jar
