/*******************************************************************************
 * Copyright (c) 2017 The University of Reading
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the University of Reading, nor the names of the
 *    authors or contributors may be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 ******************************************************************************/

package uk.org.tamsat.dataserver;

import java.io.Serializable;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.joda.time.DateTime;
import org.joda.time.chrono.ISOChronology;

import uk.ac.rdg.resc.edal.domain.Extent;
import uk.ac.rdg.resc.edal.exceptions.IncorrectDomainException;
import uk.ac.rdg.resc.edal.geometry.BoundingBoxImpl;
import uk.ac.rdg.resc.edal.geometry.Polygon;
import uk.ac.rdg.resc.edal.util.Extents;
import uk.ac.rdg.resc.edal.util.TimeUtils;

public class SubsetRequestParams implements Serializable {
    private static final long serialVersionUID = 3L;
    private final String datasetId;
    private final boolean isPoint;
    private boolean isCountry = false;
    private final Polygon bounds;
    private final Extent<DateTime> timeRange;
    private final boolean getNetcdf;
    private final JobReference jobRef;
    private String filename;
    private String countryStr;

    public SubsetRequestParams(HttpServletRequest req, Map<String, Polygon> countryBounds) {
        TamsatRequestParams params = new TamsatRequestParams(req.getParameterMap());
        datasetId = params.getMandatoryString("DATASET");
        String datatype = params.getMandatoryString("DATATYPE");
        getNetcdf = datatype.equalsIgnoreCase("netcdf");
        final String boundsStr;
        if (datatype.equalsIgnoreCase("point")) {
            double lon = params.getDouble("LON", 0f);
            double lat = params.getDouble("LAT", 0f);
            bounds = new BoundingBoxImpl(lon, lat, lon, lat);
            isPoint = true;
            boundsStr = lat + "_" + lon;
        } else {
            countryStr = params.getString("COUNTRY", "BOUNDS").toUpperCase();
            if ("BOUNDS".equals(countryStr)) {
                /*
                 * TODO Better interface than this?
                 */
                double minLon = params.getDouble("MINLON", 0f);
                double minLat = params.getDouble("MINLAT", 0f);
                double maxLon = params.getDouble("MAXLON", 0f);
                double maxLat = params.getDouble("MAXLAT", 0f);
                bounds = new BoundingBoxImpl(minLon, minLat, maxLon, maxLat);
                boundsStr = minLon + "_" + maxLon + "_" + minLat + "_" + maxLat;
            } else {
                bounds = countryBounds.get(countryStr);
                boundsStr = countryStr.toLowerCase().replaceAll(" ", "_");
                isCountry = true;
                if (bounds == null) {
                    throw new IncorrectDomainException(
                            "No boundary defined for the country: " + countryStr);
                }
            }
            isPoint = false;
        }
        DateTime startTime = TimeUtils.iso8601ToDateTime(params.getMandatoryString("STARTTIME"),
                ISOChronology.getInstanceUTC());
        DateTime endTime = TimeUtils.iso8601ToDateTime(params.getMandatoryString("ENDTIME"),
                ISOChronology.getInstanceUTC());
        timeRange = Extents.newExtent(startTime, endTime);
        String email = params.getMandatoryString("EMAIL");
        String ref = params.getMandatoryString("REF");
        filename = datasetId + "-" + timeRange.getLow().getMillis() / 1000L + "-"
                + timeRange.getHigh().getMillis() / 1000L + "_" + boundsStr
                + (getNetcdf ? ".nc" : ".csv");
        jobRef = new JobReference(email, ref);
    }

    /**
     * This Job ID is equivalent to a unique filename.
     * 
     * @return an ID which is unique for each distinct job.
     */
    public String getJobId() {
        return filename + jobRef.hashCode();
    }

    public String getFilename() {
        return filename;
    }

    public String getDatasetId() {
        return datasetId;
    }

    public boolean isPoint() {
        return isPoint;
    }

    public boolean isCountry() {
        return isCountry;
    }

    public String getCountry() {
        return countryStr;
    }

    public Polygon getBounds() {
        return bounds;
    }

    public Extent<DateTime> getTimeRange() {
        return timeRange;
    }

    public boolean isNetCDF() {
        return getNetcdf;
    }

    public JobReference getJobRef() {
        return jobRef;
    }

    @Override
    public String toString() {
        return (getNetcdf ? "NetCDF: " : "CSV: ") + datasetId + ", " + bounds + ", " + timeRange;
    }

}
