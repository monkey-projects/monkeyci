FROM docker.io/eclipse-temurin:21-jre

WORKDIR /opt/app
CMD ["java", "-Dlogback.configurationFile=config/logback.xml", "-jar", "braid-bot.jar"]

VOLUME /opt/app/config

ENV HTTP_PORT=3000
EXPOSE 3000

ADD target/braid-bot.jar /opt/app
