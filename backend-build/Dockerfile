FROM library/maven:3.3.9

RUN mkdir -p /opt/tamsat-alert/backend-build/
COPY pom.xml /opt/tamsat-alert/backend-build/
COPY src/ /opt/tamsat-alert/backend-build/src
WORKDIR /opt/tamsat-alert/backend-build/
RUN mvn package
# This is a mounted volume at the location we want the WAR (e.g. /usr/share/tomcat/webapp)
CMD cp target/tamsat-alert-server.war /mnt/build-out
