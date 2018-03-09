TAMSAT Data Subset Server
=========================

This application exposes TAMSAT NetCDF data for subsetting and timeseries extraction via a web application.  It provides 3 basic operations:

* Extraction of a timeseries at a point, in CSV format
* Extraction of an area-averaged timeseries over a defined bounding box or country, in CSV format
* Extraction of data over a defined bounding box or country, in NetCDF format

as well as providing a queueing system for managing subsetting jobs.  This will ensure that the server will not be overloaded by many simultaneous jobs, and that users will not need to remain present while jobs are running.

Building
--------

A WAR file containing the webapp may be built with the command:

```
mvn package
```

It will be output to the `target` directory and should be run on a servlet container such as tomcat.  For simpler deployment of just this application, it is recommended to use `docker-compose` in the parent directory (see `README.md` there)

Configuration
-------------

### Configuration Directory

It is recommended that the webapp is run on Tomcat, but it should work on any Java servlet container.  The configuration and temporary data directory can be configured by supplying a custom context file.  If using Tomcat, this should be placed in `${TOMCAT_HOME}/conf/Catalina/localhost/<webappname>.xml` - i.e. using the default name, the file will be called `tamsat-subset.xml`, or if deploying as the root webapp, it would be `ROOT.xml`.  The context file should define a parameter named `TamsatConfigDir` whose value is the desired location of the config directory.  An example context file is shown below:
```
<?xml version='1.0' encoding='utf-8'?>
<Context>
    <Parameter name="TamsatConfigDir" value="/usr/local/tamsat-subset" override="false"/>
</Context>
```

If this parameter is not specified, the configuration directory will be `$HOME/.tamsat/`.  If the user has no home directory (e.g. the default `tomcat` user on some systems), then the configuration directory will be in a subdirectory named `.tamsat` in the system's temporary directory.

### Configuring Datasets

To configure datasets for the service, you should add a `config.xml` file in the configuration directory.  If the service has already been run once, a skeleton file will have been created.  For each dataset, you must provide an ID, a title, and the location of the data (which will accept glob expressions).  When listing datasets to the users, they will be ordered according to the alphanumeric order of the dataset IDs.  An example `config.xml` is shown below:
```
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<config>
    <datasets>
        <dataset id="01-tamsatDaily.v3" title="TAMSAT v3 Daily" location="/usr/local/tamsat-data/daily/**/**/*.nc"/>
        <dataset id="02-tamsatPentadal.v3" title="TAMSAT v3 Pentadal" location="/usr/local/tamsat-data/pentadal/**/**/*.nc"/>
        <dataset id="03-tamsatDekadal.v3" title="TAMSAT v3 Dekadal" location="/usr/local/tamsat-data/dekadal/**/**/*.nc"/>
        <dataset id="04-tamsatMonthly.v3" title="TAMSAT v3 Monthly" location="/usr/local/tamsat-data/monthly/**/**/*.nc"/>
        <dataset id="05-tamsatSeasonal.v3" title="TAMSAT v3 Seasonal" location="/usr/local/tamsat-data/seasonal/**/*.nc"/>
    </datasets>
</config>
```

Usage
-----

The webapp should be accessed with the included client.  However, for reference, we include the supported operations here.

### GET Requests

All available GET requests are performed on the path `/data`, relative to the root webapp.  Generally speaking, the `REQUEST` URL parameter specifies the behaviour of the GET method.  The following operations are supported:

#### GETCOUNTRIES

Example request: `http://server/data?REQUEST=GETCOUNTRIES`

This returns a a JSON object mapping country names to their 3 letter codes.  When subsetting data, the country codes can be used to specify a region to subset.

Example output:
```
{"Benin":"BEN","Cameroon":"CAM","Angola":"ANG","Sudan":"SDN","Gabon":"GAB","Cote d`Ivoire":"CDI","Mozambique":"MOZ","Morocco":"MOR","Mali":"MAL","Algeria":"ALG","Lesotho":"LES","Western Sahara":"WES","South Sudan":"SSN","Tanzania":"TAN","Congo-Brazzaville":"CNG","Ghana":"GHA","Zambia":"ZAM","Guinea-Bissau":"GUB","Senegal":"SEN","Namibia":"NAM","South Africa":"SOU","Central African Republic":"CAR","Ethiopia":"ETH","Eritrea":"ERI","Burundi":"BUR","Guinea":"GIN","Egypt":"EGY","Somalia":"SOM","Chad":"CHA","Sao Tome and Principe":"STP","Madagascar":"MAD","Sierra Leone":"SIL","Equatorial Guinea":"EQG","Libya":"LAJ","Malawi":"MAA","Gambia":"GAM","Nigeria":"NIR","Tunisia":"TUN","Togo":"TOG","Niger":"NIG","Rwanda":"RWA","Kenya":"KEN","Djibouti":"DJI","Liberia":"LIB","Mauritania":"MAU","Burkina Faso":"BUF","Democratic Republic of Congo":"ZAI","Botswana":"BOT","Swaziland":"SWA","Uganda":"UGA","Zimbabwe":"ZIM"}
```

#### GETDATASETS

Example request: `http://server/data?REQUEST=GETDATASETS`

This returns a JSON array containing objects which map dataset IDs to dataset names.  This defines which datasets are available to subset data, and provides a more descriptive title for them.  An array is used so as to preserve the desired ordering of datasets.

Example output:
```
[{"01-tamsatDaily.v3":"TAMSAT v3 Daily"},{"02-tamsatPentadal.v3":"TAMSAT v3 Pentadal"},{"03-tamsatDekadal.v3":"TAMSAT v3 Dekadal"}]
```

#### GETTIMES

Example request: `http://server/data?REQUEST=GETTIMES&DATASET=01-tamsatDaily.v3`

This request takes the mandatory `DATASET` parameter.  It returns the range of dates for which the requested dataset is available, as a JSON object with keys `starttime` and `endtime`, containing values in ISO8601 format.

Example output:
```
{"starttime":"1983-01-11T00:00:00.000Z","endtime":"2018-02-15T00:00:00.000Z"}
```

#### GETDATA

Example request: `http://server/data?REQUEST=GETDATA&ID=01-tamsatDaily.v3-411091200-1518652800_30.0_0.0.csv-1212623487`

This request takes the mandatory `ID` parameter.  It returns the data file associated with the supplied job ID.  Job IDs are not directly exposed by the API


#### Getting Job Lists

Example request: `http://server/data?EMAIL=guy.griffiths@the-iea.org&REF=abc123`

By making a request with the URL parameters `EMAIL` and `REF`, a page is returned containing a list of completed jobs alongside a download link (which points to the `GETDATA` request described above).  If no parameters are supplied (e.g. `http://server/data`), a form is returned allowing a user to enter the email address and reference associated with the job.

### POST Data Subset Job

If a user wishes to start a new subsetting job, they may submit a `POST` request with the following parameters:

* `DATASET` (Mandatory) - The ID of the dataset to subset
* `DATATYPE` (Mandatory) - The type of data to be extracted.  May take the values:
    - `point` for a timeseries at a point
    - `region` for a timeseries averaged over a region
    - `netcdf` for a NetCDF subset
* `STARTTIME` (Mandatory) - The earliest time to extract data from
* `ENDTIME` (Mandatory) - The latest time to extract data from
* `EMAIL` (Mandatory) - An email address used to identify the job.  Users will be emailed on this address when their job has completed
* `REF` (Mandatory) - A group / job reference to associate with the subset task.  The aim of this is to stop other people from downloading a user's data simply by knowing their email address.  It is not so much "security" as "discouragement to casual-yet-nosey users"
* `LAT` - The latitude at which to extract a timeseries (only applies to `point` datatype)
* `LON` - The longitude at which to extract a timeseries (only applies to `point` datatype)
* `ZONE` - The the type of region to extract.  May take the value `BOUNDS` for a bounding box, otherwise a supported 3 letter country code should be supplied
* `MINLAT` - The minimum latitude of the bounding box to extract data from (only applies when `ZONE` is set to `BOUNDS`)
* `MINLON` - The minimum longitude of the bounding box to extract data from (only applies when `ZONE` is set to `BOUNDS`)
* `MAXLAT` - The maximum latitude of the bounding box to extract data from (only applies when `ZONE` is set to `BOUNDS`)
* `MAXLON` - The maximum longitude of the bounding box to extract data from (only applies when `ZONE` is set to `BOUNDS`)

### Admin Interface

There is a minimal admin interface available at `http://server/admin`.  Currently this just lists both queued and completed jobs.  To access this functionality, Tomcat (or another servlet container) should contain a security role named `tamsat-admin`.  Any users granted this role will have access to the admin interface.

Author
------

This tool was developed by [@guygriffiths](https://github.com/guygriffiths) as part of the [TAMSAT](http://www.tamsat.org.uk) project.
