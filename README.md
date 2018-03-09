TAMSAT Data Subset Server
=========================

This application exposes TAMSAT NetCDF data for subsetting and timeseries extraction via a web application.  For further details of the operation of the application, see the `README.md` file in the `backend-build` directory.

Running the Application
-----------------------

This base directory contains a `docker-compose.xml` file which can be used to run the application.  First the file should be modified so that in the `volumes` section, the local mount point for `/usr/local/tamsat-data` points at the TAMSAT data directory.  It should be structured as follows:
```
v2
├── data
│   ├── daily
│   │   ├── 1983
│   │   │   ├── 01
│   │   │   │   ├── rfe1983_01_11.v3.nc
│   │   │   │   ├── rfe1983_01_12.v3.nc
│   │   │   │   └──...
│   │   │   ├── 02
│   │   │   ├── 03
│   │   │   ├── ...
│   │   ├── 1984
│   │   │   ├── 01
│   │   │   ├── ...
│   │   ├── ...
│   ├── pentadal
│   │   ├── 1983
│   │   │   └──...
│   │   ├── 1984
│   │   └──...
│   ├── pentadal-anomalies
│   │   └──...
│   ├── dekadal
│   │   └──...
│   ├── dekadal-anomalies
│   │   └──...
│   ├── monthly
│   │   └──...
│   ├── monthly-anomalies
│   │   └──...
│   ├── seasonal
│   │   └──...
│   ├── seasonal-anomalies
│   │   └──...
...
v3
├── data
│   ├── daily
```

Once this is done, `docker-compose` can be used to build and run the application with:
```
docker-compose build
docker-compose up -d
```

After which the application will be exposed at the root level on port `80` of the host machine.  Locally, this can be accessed by visiting `http://localhost/`

Author
------

This tool was developed by [@guygriffiths](https://github.com/guygriffiths) as part of the [TAMSAT](http://www.tamsat.org.uk) project.
