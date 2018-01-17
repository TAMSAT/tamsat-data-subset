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

import java.io.File;
import java.io.Serializable;

import uk.ac.rdg.resc.edal.position.HorizontalPosition;
import uk.ac.rdg.resc.edal.util.TimeUtils;

public class FinishedJobState implements Serializable {
    private static final long serialVersionUID = 2L;
    private final SubsetRequestParams params;
    private final File fileLocation;
    private final String outputFilename;
    private long downloadedTime;
    private long completedTime;
    private boolean downloaded = false;

    public FinishedJobState(SubsetRequestParams params, File fileLocation) {
        this.params = params;
        this.fileLocation = fileLocation;
        /* Job ID is a unique (per unique job) filename */
        outputFilename = params.getJobId();
        downloadedTime = -1L;
        completedTime = System.currentTimeMillis();
    }
    
    public String getId() {
        return params.getJobId();
    }

    public File getFileLocation() {
        return fileLocation;
    }
    
    public String getOutputFilename() {
        return outputFilename;
    }

    public JobReference getJobRef() {
        return params.getJobRef();
    }
    
    public String getJobDescription() {
        StringBuilder sb = new StringBuilder();
        if(params.isNetCDF()) {
            sb.append("NetCDF subset of ");
        } else {
            sb.append("Timeseries of ");
        }
        if(params.getBbox().getWidth() != 0) {
            sb.append("region:<br />"+params.getBbox());
        } else {
            HorizontalPosition pos = params.getBbox().getLowerCorner();
            sb.append("(Lat: "+pos.getY()+", Lon: "+pos.getX()+")");
        }
        sb.append("<br />Between "+TimeUtils.formatUtcDateOnly(params.getTimeRange().getLow())+" and ");
        sb.append(TimeUtils.formatUtcDateOnly(params.getTimeRange().getHigh()));
        return sb.toString();
    }
    
    public long getCompletedTime() {
        return completedTime;
    }
    
    public long getDownloadedTime() {
        return downloadedTime;
    }
    
    public void setDownloaded() {
        downloaded = true;
        downloadedTime = System.currentTimeMillis();
    }
    
    public boolean wasDownloaded() {
        return downloaded;
    }
}
