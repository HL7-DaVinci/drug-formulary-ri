FROM maven:3.6.1-jdk-8 AS build
COPY pom.xml /usr/src/app/pom.xml
RUN mvn -f /usr/src/app/pom.xml verify --fail-never
COPY src /usr/src/app/src
RUN mvn -f /usr/src/app/pom.xml clean package

FROM jetty:9-jre8-alpine
COPY --from=build /usr/src/app/target/root.war /var/lib/jetty/webapps/root.war
ADD ./data /var/lib/jetty/target
USER root
RUN chown -R jetty:jetty /var/lib/jetty/target
USER jetty:jetty
EXPOSE 8080
