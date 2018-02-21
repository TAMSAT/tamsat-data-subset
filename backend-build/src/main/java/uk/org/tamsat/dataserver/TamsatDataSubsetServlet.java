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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.geotools.data.DataStore;
import org.geotools.data.DataStoreFinder;
import org.geotools.data.FeatureSource;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.joda.time.DateTime;
import org.json.JSONObject;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vividsolutions.jts.geom.Geometry;

import uk.ac.rdg.resc.edal.catalogue.DataCatalogue;
import uk.ac.rdg.resc.edal.dataset.Dataset;
import uk.ac.rdg.resc.edal.domain.Extent;
import uk.ac.rdg.resc.edal.domain.TemporalDomain;
import uk.ac.rdg.resc.edal.util.GISUtils;
import uk.ac.rdg.resc.edal.util.TimeUtils;
import uk.org.tamsat.dataserver.SubsetJob.JobFinished;
import uk.org.tamsat.dataserver.util.CountryDefinition;
import uk.org.tamsat.dataserver.util.JobListing;

/**
 * A servlet which handles the queueing of
 *
 * @author Guy Griffiths
 */
public class TamsatDataSubsetServlet extends HttpServlet implements JobFinished, JobListing {
    private static final String COMPLETED_JOBLIST_FILENAME = "joblist-completed.dat";
    private static final String SUBMITTED_JOBLIST_FILENAME = "joblist-submitted.dat";

    private static final long serialVersionUID = 1L;

    private Map<String, SubsetRequestParams> submittedJobs = new HashMap<>();
    private ExecutorService jobQueue;
    private ScheduledExecutorService cleaner;

    private Map<String, CountryDefinition> countryBounds;
    private DataCatalogue tamsatCatalogue;

    private File dataDir;

    private Map<String, FinishedJobState> ids2Jobs = new HashMap<>();
    private Map<JobReference, List<FinishedJobState>> jobRef2Jobs = new HashMap<>();
    private List<FinishedJobState> finishedJobs = new ArrayList<>();

    private VelocityEngine velocityEngine;

    private static final Logger log = LoggerFactory.getLogger(TamsatDataSubsetServlet.class);

    @Override
    @SuppressWarnings("unchecked")
    public void init(ServletConfig servletConfig) throws ServletException {
        super.init(servletConfig);

        /*
         * Expose the job listing interface, so that the admin servlet can
         * access it.
         */
        servletConfig.getServletContext().setAttribute(TamsatApplicationServlet.CONTEXT_JOB_LISTING,
                this);

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
            throw new ServletException(
                    "Config directory is badly defined.  This is probably a bug, please report it to the system administrator.");
        }

        Object ve = servletConfig.getServletContext()
                .getAttribute(TamsatApplicationServlet.CONTEXT_VELOCITY_ENGINE);
        if (ve instanceof VelocityEngine) {
            velocityEngine = (VelocityEngine) ve;
        } else {
            throw new ServletException(
                    "Velocity template engine is badly defined.  This is probably a bug, please report it to the system administrator.");
        }
        
        /*
         * Load definition of countries
         */
        URL africaShp = getClass().getResource("/shapefiles/Africa.shp");
        countryBounds = loadCountriesFromShapefile(africaShp);

        /*
         * If list of persisted running jobs exists, load it into memory and set
         * jobs to run again
         */
        File persistedRunningJobs = new File(dataDir, SUBMITTED_JOBLIST_FILENAME);
        if (persistedRunningJobs.exists()) {
            try (FileInputStream fis = new FileInputStream(persistedRunningJobs);
                    ObjectInputStream ois = new ObjectInputStream(fis)) {
                Object obj = ois.readObject();
                if (obj instanceof Map) {
                    submittedJobs = (Map<String, SubsetRequestParams>) obj;

                    /*
                     * Now run all of the jobs
                     */
                    for (String key : submittedJobs.keySet()) {
                        SubsetRequestParams subsetParams = submittedJobs.get(key);
                        jobQueue.submit(
                                new SubsetJob(subsetParams, tamsatCatalogue, dataDir, this));
                        submittedJobs.put(subsetParams.getJobId(), subsetParams);
                    }
                    saveSubmittedJobList();
                }
            } catch (Throwable e) {
                log.error(
                        "Problem reading persisted job list.  Jobs running in previous sessions will need to be re-run manually",
                        e);
            }
        }

        /*
         * If persisted job list exists, load it into memory
         */
        File persistedCompletedJobs = new File(dataDir, COMPLETED_JOBLIST_FILENAME);
        if (persistedCompletedJobs.exists()) {
            try (FileInputStream fis = new FileInputStream(persistedCompletedJobs);
                    ObjectInputStream ois = new ObjectInputStream(fis)) {
                Object obj = ois.readObject();
                if (obj instanceof List) {
                    finishedJobs = (List<FinishedJobState>) obj;
                    /*
                     * Now build the Maps of IDs 2 jobs and email 2 job lists
                     * for easier retrieval
                     */
                    for (FinishedJobState job : finishedJobs) {
                        ids2Jobs.put(job.getId(), job);
                        if (!jobRef2Jobs.containsKey(job.getJobRef())) {
                            jobRef2Jobs.put(job.getJobRef(), new ArrayList<>());
                        }
                        jobRef2Jobs.get(job.getJobRef()).add(job);
                    }
                }
            } catch (Throwable e) {
                log.error(
                        "Problem reading persisted job list.  Jobs from previous sessions will not be available",
                        e);
            }
        }

        /*
         * Set-up service to check completed jobs to see when they have been
         * downloaded more than 24 hours ago, or completed over 7 days ago (and
         * not downloaded)
         */
        cleaner = Executors.newScheduledThreadPool(1);
        Runnable cleanupJob = new Runnable() {
            @Override
            public void run() {
                List<FinishedJobState> expired = new ArrayList<>();
                for (FinishedJobState job : finishedJobs) {
                    if ((job.wasDownloaded()
                            && (System.currentTimeMillis() - job.getDownloadedTime()) > 1000 * 60
                                    * 60 * 24)
                            || (System.currentTimeMillis() - job.getCompletedTime()) > 1000 * 60
                                    * 60 * 24 * 7) {
                        log.debug("Job " + job.getId() + " has expired");
                        if (job.wasDownloaded()) {
                            log.debug("It was downloaded at " + job.getDownloadedTime() + " (now "
                                    + System.currentTimeMillis() + ")");
                        } else {
                            log.debug("It was created at " + job.getCompletedTime() + " (now "
                                    + System.currentTimeMillis() + ")");
                        }
                        expired.add(job);
                        File expiredFile = new File(dataDir, job.getId());
                        boolean delete = expiredFile.delete();
                        log.debug(delete ? "Delete worked" : "Delete failed");
                    }
                }
                if (expired.size() > 0) {
                    /*
                     * Remove any expired jobs from the job list and save it
                     */
                    for (FinishedJobState x : expired) {
                        deleteFinishedJob(x);
                    }
                    saveCompletedJobList();
                }
            }
        };
        /*
         * Run the cleanup job every 15 minutes
         */
        cleaner.scheduleWithFixedDelay(cleanupJob, 15, 15, TimeUnit.MINUTES);

        log.debug("Data subset servlet started");
    }
    
    static Map<String, CountryDefinition> loadCountriesFromShapefile(URL shapefile) {
        Map<String, CountryDefinition> ret = new HashMap<>();
        try {
            Collection<SimpleFeature> features = getFeatures(shapefile);
            Pattern countryIdPattern = Pattern.compile("([A-Za-z]+)[0-9]*");
            Map<String, String> id2Label = new HashMap<>();
            Map<String, List<Geometry>> id2Geometries = new HashMap<>();
            for (SimpleFeature feature : features) {
                /*
                 * ID is 3 letters + number. Same letters
                 */
                String id = feature.getAttribute("ID").toString();
                Matcher m = countryIdPattern.matcher(id);
                if (!m.matches()) {
                    log.warn("Unexpected country ID: " + id);
                    continue;
                }
                String countryId = m.group(1);
                
                Object caption = feature.getAttribute("CAPTION");
                if (caption != null && !caption.toString().isEmpty()) {
                    id2Label.put(countryId, caption.toString());
                }

                if(!id2Geometries.containsKey(countryId)) {
                    id2Geometries.put(countryId, new ArrayList<>());
                }
                Geometry geometry = (Geometry) feature.getDefaultGeometry();
                id2Geometries.get(countryId).add(geometry);
            }
            for(String countryId : id2Label.keySet()) {
                ret.put(countryId, new CountryDefinition(id2Label.get(countryId), id2Geometries.get(countryId)));
            }
        } catch (IOException e) {
            log.warn(
                    "Problem reading list of country bounds.  Not all countries will be available to subset");
        }
        return ret;
    }

    public static void main(String[] args) throws IOException {
        URL resource = TamsatDataSubsetServlet.class.getResource("/shapefiles/Africa.shp");
        Collection<SimpleFeature> features = getFeatures(resource);
        for (SimpleFeature f : features) {
            System.out.println(f.getAttribute("CAPTION"));
        }
    }

    /**
     * Reads a set of {@link SimpleFeature}s from a shapefile
     *
     * @param shapefilePath
     *            The location of the .shp file
     * @return A {@link Collection} of {@link SimpleFeature}s contained within
     *         the shapefile
     * @throws IOException
     *             If there is a problem reading the shapefile
     * @throws DataStoreException
     */
    protected static Collection<SimpleFeature> getFeatures(URL shapefile) throws IOException {
        Map<String, Object> map = new HashMap<>();
        map.put("url", shapefile);

        DataStore dataStore = DataStoreFinder.getDataStore(map);
        String typeName = dataStore.getTypeNames()[0];

        FeatureSource<SimpleFeatureType, SimpleFeature> source = dataStore
                .getFeatureSource(typeName);

        FeatureCollection<SimpleFeatureType, SimpleFeature> collection = source.getFeatures();
        Collection<SimpleFeature> features = new ArrayList<>();
        try (FeatureIterator<SimpleFeature> simpleFeatures = collection.features()) {
            while (simpleFeatures.hasNext()) {
                features.add(simpleFeatures.next());
            }
        }
        dataStore.dispose();
        return features;
    }

    @Override
    public void destroy() {
        super.destroy();
        jobQueue.shutdown();
        tamsatCatalogue.shutdown();
        cleaner.shutdown();
        GISUtils.releaseEpsgDatabase();
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
        TamsatRequestParams params = new TamsatRequestParams(req.getParameterMap());
        String method = params.getString("REQUEST", null);
        if (method == null) {
            /*
             * No parameters added, just show available subsets
             */
            String email = params.getString("EMAIL");
            String ref = params.getString("REF");

            Template template = velocityEngine.getTemplate("templates/joblist.vm");
            VelocityContext context = new VelocityContext();
            context.put("email", email);
            context.put("ref", ref);
            if (email != null && ref != null) {
                List<FinishedJobState> jobs = jobRef2Jobs.get(new JobReference(email, ref));
                context.put("jobs", jobs);
            }
            try {
                template.merge(context, resp.getWriter());
            } catch (Exception e) {
                log.error("Problem returning joblist", e);
                throw new ServletException("Problem returning job list", e);
            }
        } else if (method.equalsIgnoreCase("GETCOUNTRIES")) {
            JSONObject countries = new JSONObject();
            for (Entry<String, CountryDefinition> country : countryBounds.entrySet()) {
                countries.put(country.getValue().getLabel(), country.getKey());
            }
            resp.setContentType("application/json");
            try {
                resp.getWriter().write(countries.toString());
            } catch (IOException e) {
                log.error("Problem writing country list to output stream", e);
                throw new ServletException("Problem writing JSON to output stream", e);
            }
        } else if (method.equalsIgnoreCase("GETTIMES")) {
            /*
             * This returns the available time range for the desired dataset
             */
            String datasetId = params.getMandatoryString("DATASET");
            Dataset dataset = tamsatCatalogue.getDatasetFromId(datasetId);
            if (dataset == null) {
                throw new ServletException(
                        "Data is not yet loaded on the server - please try again in 5 minutes");
            }
            Set<String> varIds = dataset.getVariableIds();
            DateTime startTime = null;
            DateTime endTime = null;
            for (String varId : varIds) {
                TemporalDomain tDomain = dataset.getVariableMetadata(varId).getTemporalDomain();
                Extent<DateTime> tExtent = tDomain.getExtent();
                if (startTime == null || tExtent.getLow().isBefore(startTime)) {
                    startTime = tExtent.getLow();
                }
                if (endTime == null || tExtent.getHigh().isAfter(endTime)) {
                    endTime = tExtent.getHigh();
                }
            }
            JSONObject startEndTimes = new JSONObject();
            startEndTimes.put("starttime", TimeUtils.dateTimeToISO8601(startTime));
            startEndTimes.put("endtime", TimeUtils.dateTimeToISO8601(endTime));
            resp.setContentType("application/json");
            try {
                resp.getWriter().write(startEndTimes.toString());
            } catch (IOException e) {
                log.error("Problem writing metadata to output stream", e);
                throw new ServletException("Problem writing JSON to output stream", e);
            }
        } else if (method.equalsIgnoreCase("GETDATA")) {
            /*
             * Requests a data file from a previously completed job
             */
            String id = params.getMandatoryString("ID");
            FinishedJobState finishedJobState = ids2Jobs.get(id);
            if (finishedJobState == null) {
                throw new ServletException("The job ID " + id + " does not exist.");
            }
            File fileToServe = finishedJobState.getFileLocation();
            if (!fileToServe.exists()) {
                throw new ServletException("The ID " + id
                        + " does not refer to an existing data file.  Perhaps you have already downloaded this data file?");
            }

            /*
             * Now return the data, with appropriate filename and MIME type
             */
            resp.setHeader("Content-Disposition",
                    "inline; filename=" + finishedJobState.getOutputFilename());
            resp.setContentType(finishedJobState.getOutputFilename().endsWith("csv") ? "text/csv"
                    : "application/x-netcdf");
            try (FileInputStream is = new FileInputStream(fileToServe);
                    ServletOutputStream os = resp.getOutputStream()) {
                int n;
                byte[] buffer = new byte[1024];
                while ((n = is.read(buffer)) > -1) {
                    os.write(buffer, 0, n); // Don't allow any extra bytes to creep in, final write
                }
            }
            finishedJobState.setDownloaded();

            saveCompletedJobList();
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        SubsetRequestParams subsetParams;
        try {
            /*
             * Parse required parameters. This will throw an exception if they
             * are not present
             */
            TamsatRequestParams reqParams = new TamsatRequestParams(req.getParameterMap());
            subsetParams = new SubsetRequestParams(reqParams, countryBounds);

            /*
             * Add the job to the queue
             */
            log.debug("Adding job " + subsetParams.getJobId() + " to the queue");
            jobQueue.submit(new SubsetJob(subsetParams, tamsatCatalogue, dataDir, this));
            submittedJobs.put(subsetParams.getJobId(), subsetParams);
            saveSubmittedJobList();
        } catch (Exception e) {
            log.error("Problem parsing parameters and adding job", e);
            throw new ServletException("Problem submitting subset job.", e);
        }

        Template template = velocityEngine.getTemplate("templates/job_posted.vm");
        VelocityContext context = new VelocityContext();
        context.put("email", subsetParams.getJobRef().email);
        context.put("ref", subsetParams.getJobRef().ref);
        try {
            template.merge(context, resp.getWriter());
        } catch (Exception e) {
            log.error("Problem returning page after job posted", e);
            throw new ServletException("Problem returning page after job posted", e);
        }
    }

    @Override
    public synchronized void jobFinished(FinishedJobState state) {
        /*
         * Remove job from running job list
         */
        submittedJobs.remove(state.getId());

        if (state.success()) {
            log.debug("Saving completed job " + state.getId());

            addFinishedJob(state);
        } else {
            log.error("Problem completing job " + state.getId(), state.getException());
        }

        saveCompletedJobList();
        saveSubmittedJobList();
    }

    public List<FinishedJobState> getFinishedJobs() {
        return finishedJobs;
    }

    public Map<String, SubsetRequestParams> getQueuedJobs() {
        return submittedJobs;
    }

    private synchronized void addFinishedJob(FinishedJobState state) {
        /*
         * Add job state to appropriate Maps for easy retrieval
         */
        if (!jobRef2Jobs.containsKey(state.getJobRef())) {
            jobRef2Jobs.put(state.getJobRef(), new ArrayList<>());
        }
        jobRef2Jobs.get(state.getJobRef()).add(state);
        ids2Jobs.put(state.getId(), state);

        /*
         * Add job state to list of jobs and persist
         */
        finishedJobs.add(state);
    }

    private synchronized void deleteFinishedJob(FinishedJobState state) {
        List<FinishedJobState> list = jobRef2Jobs.get(state.getJobRef());
        if (list != null) {
            list.remove(state);
            /* If that was the last job with that state, remove it */
            if (list.size() == 0) {
                jobRef2Jobs.remove(state.getJobRef());
            }
        }
        ids2Jobs.remove(state.getId());

        finishedJobs.remove(state);
    }

    /**
     * Saves the list of finished jobs to disk
     */
    private synchronized void saveCompletedJobList() {
        try (FileOutputStream fos = new FileOutputStream(
                new File(dataDir, COMPLETED_JOBLIST_FILENAME));
                ObjectOutputStream oos = new ObjectOutputStream(fos)) {
            oos.writeObject(finishedJobs);
            log.debug("Completed job list written to file");
        } catch (IOException e) {
            log.error(
                    "Problem writing completed job list.  Persistence will not work across restarts");
        }
    }

    /**
     * Saves the list of submitted (but not completed) jobs to disk
     */
    private synchronized void saveSubmittedJobList() {
        try (FileOutputStream fos = new FileOutputStream(
                new File(dataDir, SUBMITTED_JOBLIST_FILENAME));
                ObjectOutputStream oos = new ObjectOutputStream(fos)) {
            oos.writeObject(submittedJobs);
            log.debug("Running job list written to file");
        } catch (IOException e) {
            log.error(
                    "Problem writing running job list.  Persistence will not work across restarts",
                    e);
        }
    }
}
