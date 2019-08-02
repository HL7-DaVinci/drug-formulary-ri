FROM jetty:9-jre8-alpine
USER jetty:jetty
ADD ./target/formulary.war /var/lib/jetty/webapps/formulary.war
ADD ./data /var/lib/jetty/target
USER root
RUN chown -R jetty:jetty /var/lib/jetty/target
USER jetty:jetty
EXPOSE 8080
