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

import javax.servlet.http.HttpServletRequest;

import org.joda.time.DateTime;
import org.joda.time.chrono.ISOChronology;

import uk.ac.rdg.resc.edal.domain.Extent;
import uk.ac.rdg.resc.edal.geometry.BoundingBox;
import uk.ac.rdg.resc.edal.geometry.BoundingBoxImpl;
import uk.ac.rdg.resc.edal.util.Extents;
import uk.ac.rdg.resc.edal.util.TimeUtils;

public class SubsetRequestParams implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String datasetId;
    private final BoundingBox bbox;
    private final Extent<DateTime> timeRange;
    private final boolean getNetcdf;
    private final String email;

    public SubsetRequestParams(HttpServletRequest req) {
        TamsatRequestParams params = new TamsatRequestParams(req.getParameterMap());
        datasetId = params.getMandatoryString("DATASET");
        String datatype = params.getMandatoryString("DATATYPE");
        getNetcdf = datatype.equalsIgnoreCase("netcdf");
        if (datatype.equalsIgnoreCase("point")) {
            double lon = params.getDouble("LON", 0f);
            double lat = params.getDouble("LAT", 0f);
            bbox = new BoundingBoxImpl(lon, lat, lon, lat);
        } else {
            double minLon = params.getDouble("MINLON", 0f);
            double minLat = params.getDouble("MINLAT", 0f);
            double maxLon = params.getDouble("MAXLON", 0f);
            double maxLat = params.getDouble("MAXLAT", 0f);
            bbox = new BoundingBoxImpl(minLon, minLat, maxLon, maxLat);
        }
        DateTime startTime = TimeUtils.iso8601ToDateTime(params.getMandatoryString("STARTTIME"), ISOChronology.getInstanceUTC());
        DateTime endTime = TimeUtils.iso8601ToDateTime(params.getMandatoryString("ENDTIME"), ISOChronology.getInstanceUTC());
        timeRange = Extents.newExtent(startTime, endTime);
        email = params.getMandatoryString("EMAIL");
    }

    public String getDatasetId() {
        return datasetId;
    }

    public BoundingBox getBbox() {
        return bbox;
    }

    public Extent<DateTime> getTimeRange() {
        return timeRange;
    }

    public boolean isNetCDF() {
        return getNetcdf;
    }

    public String getEmail() {
        return email;
    }

    @Override
    public String toString() {
        return (getNetcdf ? "NetCDF: " : "CSV: ") + datasetId + ", " + bbox + ", " + timeRange;
    }

    /*
     * hashCode() and equals() ignore email - this way, two identical subset
     * requests with different emails do not necessarily need to be performed
     * twice
     */

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((bbox == null) ? 0 : bbox.hashCode());
        result = prime * result + ((datasetId == null) ? 0 : datasetId.hashCode());
        result = prime * result + (getNetcdf ? 1231 : 1237);
        result = prime * result + ((timeRange == null) ? 0 : timeRange.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        SubsetRequestParams other = (SubsetRequestParams) obj;
        if (bbox == null) {
            if (other.bbox != null)
                return false;
        } else if (!bbox.equals(other.bbox))
            return false;
        if (datasetId == null) {
            if (other.datasetId != null)
                return false;
        } else if (!datasetId.equals(other.datasetId))
            return false;
        if (getNetcdf != other.getNetcdf)
            return false;
        if (timeRange == null) {
            if (other.timeRange != null)
                return false;
        } else if (!timeRange.equals(other.timeRange))
            return false;
        return true;
    }
}
