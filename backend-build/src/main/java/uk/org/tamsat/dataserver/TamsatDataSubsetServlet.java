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
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.ac.rdg.resc.edal.catalogue.DataCatalogue;
import uk.ac.rdg.resc.edal.dataset.Dataset;
import uk.ac.rdg.resc.edal.dataset.GriddedDataset;
import uk.ac.rdg.resc.edal.exceptions.EdalException;
import uk.ac.rdg.resc.edal.wms.RequestParams;
import uk.org.tamsat.dataserver.SubsetJob.JobFinished;

/**
 * A servlet which handles the queueing of
 *
 * @author Guy Griffiths
 */
public class TamsatDataSubsetServlet extends HttpServlet implements JobFinished {
    private static final long serialVersionUID = 1L;

    private final List<Future<Integer>> runningJobs = new ArrayList<>();
    private ExecutorService jobQueue;

    private DataCatalogue tamsatCatalogue;

    private File dataDir;
    
    private Map<Integer, FinishedJobState> ids2Jobs = new HashMap<>();

    private static final Logger log = LoggerFactory.getLogger(TamsatDataSubsetServlet.class);

    @Override
    public void init(ServletConfig servletConfig) throws ServletException {
        super.init(servletConfig);
        /*
         * Retrieve the pre-loaded catalogue and wire it up
         */
        Object config = servletConfig.getServletContext()
                .getAttribute(TamsatApplicationServlet.CONTEXT_TAMSAT_CATALOGUE);
        if (config instanceof DataCatalogue) {
            tamsatCatalogue = (DataCatalogue) config;
        } else {
            String message;
            if (config == null) {
                message = "TAMSAT configuration object is null";
            } else {
                message = "TAMSAT configuration object is incorrect type:" + config.getClass()
                        + "\nThe \"" + TamsatApplicationServlet.CONTEXT_TAMSAT_CATALOGUE
                        + "\" attribute of the ServletContext has been incorrectly set.";
            }
            throw new ServletException(message);
        }

        int nThreads = Runtime.getRuntime().availableProcessors() - 1;
        if (nThreads < 1) {
            nThreads = 1;
        }
        jobQueue = Executors.newFixedThreadPool(nThreads);

        Object configDir = servletConfig.getServletContext()
                .getAttribute(TamsatApplicationServlet.CONTEXT_CONFIG_DIR);
        if (configDir instanceof String) {
            /*
             * TODO allow this to be specifically configured
             */
            dataDir = new File((String) configDir, "tmp_data");
            if (!dataDir.exists()) {
                /*
                 * TODO this needs to be more robust
                 */
                dataDir.mkdirs();
            }
        } else {
            throw new ServletException("Config directory is badly defined.  This is a bug");
        }

        /*
         * Set-up service to regularly check the list of running jobs to see w
         */

        log.debug("Data subset servlet started");
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        /*
         * Should check for the ID of the data file to download (i.e. the hash
         * code of the subset job), and return the data file, if it exists.
         * 
         * Once successfully transferred, mark the data file for deletion. Set
         * up something to delete the actual data files some amount of time
         * after they have been downloaded (say a day), so that multiple
         * downloads can be done if required.
         */
        RequestParams params = new RequestParams(req.getParameterMap());
        try {
            int id = params.getMandatoryPositiveInt("ID");
            FinishedJobState finishedJobState = ids2Jobs.get(id);
            if(finishedJobState == null) {
                throw new ServletException("The ID " + id
                        + " does not refer to an existing data file.  Perhaps you have already downloaded this data file?");
            }
            File fileToServe = finishedJobState.getFileLocation();
            if (!fileToServe.exists()) {
                throw new ServletException("The ID " + id
                        + " does not refer to an existing data file.  Perhaps you have already downloaded this data file?");
            }
            
            /*
             * TODO generate a more meaningful filename.
             * 
             * Or at least one which represents the type of data being returned.
             */
            resp.setHeader("Content-Disposition", "inline; filename=" + "tamsat-subset.txt");
            try (FileInputStream is = new FileInputStream(fileToServe);
                    ServletOutputStream os = resp.getOutputStream()) {
                int n;
                byte[] buffer = new byte[1024];
                while ((n = is.read(buffer)) > -1) {
                    os.write(buffer, 0, n); // Don't allow any extra bytes to creep in, final write
                }
            }
        } catch (EdalException e) {
            /*
             * The ID parameter either doesn't exist, or is not an integer.
             */
            throw new ServletException("Must provide integer ID of job number to download", e);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        SubsetRequestParams subsetParams = new SubsetRequestParams(req);

        /*
         * Needs to get parameters:
         * 
         * region(s), time range, email address, subset/average
         */
        Dataset dataset = tamsatCatalogue.getDatasetFromId(subsetParams.getDatasetId());
        if (!(dataset instanceof GriddedDataset)) {
            throw new ServletException("Only gridded datasets may be subset");
        }

        /*
         * Add the job to the queue
         */
        log.debug("Adding job " + subsetParams.hashCode() + " to the queue");
        Future<Integer> job = jobQueue
                .submit(new SubsetJob(subsetParams, (GriddedDataset) dataset, dataDir, this));
        runningJobs.add(job);
    }

    @Override
    public synchronized void jobFinished(FinishedJobState state) {
        /*
         * This gets called once a job has finished.        
         */
        ids2Jobs.put(state.getId(), state);
        log.debug("Saving completed job "+state.getId());
    }
}
