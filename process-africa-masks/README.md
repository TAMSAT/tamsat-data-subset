TAMSAT Mask Processing
======================

This application takes a shapefile containing African countries and an example TAMSAT NetCDF file, and produces a data file containing a list of the grid cell indices which appear in each country.  This is designed for use with the TAMSAT data subset software.

This motivation for this is that there are certain library clashes between the shapefile libraries for Java, and EDAL, which the TAMSAT subset server heavily relies upon.  By creating an intermediate data format, shapefile support does not need to be included for the TAMSAT subset server.

Note that this program takes a significant amount of time to run (several hours).  The output has already been stored in source control under `../backend-build/src/main/resources/africa_masks/countries.dat`, but this can be used to re-generate for e.g. a different grid, new country borders etc.

Usage
-----

The software may be run with the command

```
mvn exec:java -Dexec.args="/path/to/output/file.dat"
```



Output Format
-------------

The output format is an ASCII text file containing pairs of lines of the form:

```
COUNTRY_ID:Country Label
x1 y1,x2 y2,x3 y3,x4 y4,
```

where `x1 y1` etc. are the coordinate _indices_ within the NetCDF data file which are within each country.