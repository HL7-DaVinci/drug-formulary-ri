FROM jetty:9-jre8-alpine
USER jetty:jetty
ADD ./target/formulary.war /var/lib/jetty/webapps/formulary.war
EXPOSE 8080
