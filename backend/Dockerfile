FROM library/tomcat:8.5
RUN apt-get update
RUN apt-get install -y libnetcdf-dev
ENV JAVA_OPTS="-Xmx6G"
COPY tamsat-subset.xml /usr/local/tomcat/conf/Catalina/localhost/ROOT.xml

COPY config.xml /usr/local/tamsat-subset/
