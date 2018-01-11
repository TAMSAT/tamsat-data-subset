FROM library/tomcat:8.5
RUN apt-get update
RUN apt-get install -y libnetcdf-dev
ENV JAVA_OPTS="-Xmx768M"
COPY tamsat-subset.xml /usr/local/tomcat/conf/Catalina/localhost/
RUN mkdir /usr/local/tamsat-subset

COPY config.xml /usr/local/tamsat-subset/
# Copy the data here
COPY data/ /usr/local/tamsat-data/
