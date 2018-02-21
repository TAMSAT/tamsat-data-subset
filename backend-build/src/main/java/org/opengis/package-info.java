/**
 * Here we replicate several classes from the org.opengis:geoapi:3.0.0 package
 * imported via edal-common.
 * 
 * The required libraries for shapefile manipulation pull in an incompatible
 * version of the GeoAPI, named gt-opengis-18.2.jar, which (being alphabetically
 * after geoapi-3.0.0.jar) takes precedence over the one required by EDAL.
 * 
 * Incompatible classes which get clobbered by gt-opengis (but which are not
 * actually required for it) are reimplemented here, so that they take
 * precedence by being in WEB-INF/classes rather than WEB-INF/lib
 */

package org.opengis;