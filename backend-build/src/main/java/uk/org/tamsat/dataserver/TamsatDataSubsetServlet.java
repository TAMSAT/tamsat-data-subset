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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.URL;
import java.security.Security;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.joda.time.DateTime;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.ac.rdg.resc.edal.dataset.Dataset;
import uk.ac.rdg.resc.edal.domain.Extent;
import uk.ac.rdg.resc.edal.domain.TemporalDomain;
import uk.ac.rdg.resc.edal.geometry.BoundingBox;
import uk.ac.rdg.resc.edal.util.GISUtils;
import uk.ac.rdg.resc.edal.util.GridCoordinates2D;
import uk.ac.rdg.resc.edal.util.TimeUtils;
import uk.org.tamsat.dataserver.SubsetJob.JobFinished;
import uk.org.tamsat.dataserver.util.CountryDefinition;
import uk.org.tamsat.dataserver.util.JobListing;
import uk.org.tamsat.dataserver.util.TamsatCatalogue;
import uk.org.tamsat.dataserver.util.TamsatCatalogueConfig.EmailInfo;

/**
 * A servlet which handles the queueing of data subsetting/averaging jobs
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
    private TamsatCatalogue tamsatCatalogue;

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
        if (config instanceof TamsatCatalogue) {
            log.debug("Data subset servlet getting data catalogue");
            tamsatCatalogue = (TamsatCatalogue) config;
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
        if (nThreads < 2) {
            nThreads = 2;
        }
        log.debug("Using " + nThreads + " threads for data subsetting");
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
        log.debug("Prepared temporary data directory at " + dataDir.getAbsolutePath());

        Object ve = servletConfig.getServletContext()
                .getAttribute(TamsatApplicationServlet.CONTEXT_VELOCITY_ENGINE);
        if (ve instanceof VelocityEngine) {
            velocityEngine = (VelocityEngine) ve;
        } else {
            throw new ServletException(
                    "Velocity template engine is badly defined.  This is probably a bug, please report it to the system administrator.");
        }

        log.debug("Loading country definitions");
        /*
         * Load definition of countries
         */
        URL africaMasks = getClass().getResource("/africa_masks.dat");
        try {
            countryBounds = loadCountryMasks(africaMasks);
            log.debug(countryBounds.size() + " country definitions loaded");
        } catch (IOException e) {
            log.error("Problem loading country masks.  Subsetting by country will not be available",
                    e);
        }

        /*
         * If list of persisted running jobs exists, load it into memory and set
         * jobs to run again
         */
        File persistedRunningJobs = new File(dataDir, SUBMITTED_JOBLIST_FILENAME);
        if (persistedRunningJobs.exists()) {
            log.debug("Bringing back uncompleted jobs from previous session");
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
                log.debug("Previous jobs set running");
            } catch (Throwable e) {
                log.error(
                        "Problem reading persisted job list.  Jobs running in previous sessions will need to be re-run manually",
                        e);
            }
        }

        /*
         * If persisted job list exists, load it into memory
         */
        log.debug("Loading information about previously completed jobs");
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
                    /*
                     * TODO Increase available time, since server was down
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

    static Map<String, CountryDefinition> loadCountryMasks(URL africaMasks) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(africaMasks.openStream()));
        String line;
        Map<String, CountryDefinition> ret = new HashMap<>();
        while ((line = r.readLine()) != null) {
            String[] countryParts = line.split(":");
            if (countryParts.length != 3) {
                throw new IllegalArgumentException(
                        "Invalid format - odd lines must contain \"ID:Label:BBOX\"");
            }
            String id = countryParts[0];
            String label = countryParts[1];
            BoundingBox bbox = GISUtils.parseBbox(countryParts[2], true, "CRS:84");

            line = r.readLine();
            if (line == null || line.isEmpty()) {
                continue;
            }
            String[] gridCoordsStrs = line.split(",");
            List<GridCoordinates2D> cells = new ArrayList<>();
            for (String gridCoordStr : gridCoordsStrs) {
                String[] xy = gridCoordStr.split(" ");
                if (xy.length == 2) {
                    cells.add(new GridCoordinates2D(Integer.parseInt(xy[0]),
                            Integer.parseInt(xy[1])));
                }
            }
            ret.put(id, new CountryDefinition(label, cells, bbox));
        }
        return ret;
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
        TamsatRequestParams params = new TamsatRequestParams(req.getParameterMap());
        String method = params.getString("REQUEST", null);
        if (method == null) {
            showCompleted(params, resp);
        } else if (method.equalsIgnoreCase("GETCOUNTRIES")) {
            getCountries(resp);
        } else if (method.equalsIgnoreCase("GETDATASETS")) {
            getDatasets(resp);
        } else if (method.equalsIgnoreCase("GETTIMES")) {
            getTimes(params, resp);
        } else if (method.equalsIgnoreCase("GETDATA")) {
            getData(params, resp);
        }
    }

    private void showCompleted(TamsatRequestParams params, HttpServletResponse resp)
            throws ServletException {
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
    }

    private void getCountries(HttpServletResponse resp) throws ServletException {
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
    }

    private void getDatasets(HttpServletResponse resp) throws ServletException {
        JSONArray datasets = new JSONArray();
        /*
         * Sort the datasets by ID. This allows the order to be configured by
         * simply giving the IDs a numerical prefix.
         */
        List<String> datasetIds = new ArrayList<>();
        for (Dataset ds : tamsatCatalogue.getAllDatasets()) {
            datasetIds.add(ds.getId());
        }
        Collections.sort(datasetIds);
        for (String dsId : datasetIds) {
            JSONObject dsObj = new JSONObject();
            dsObj.put(dsId, tamsatCatalogue.getDatasetInfo(dsId).getTitle());
            datasets.put(dsObj);
        }

        resp.setContentType("application/json");
        try {
            resp.getWriter().write(datasets.toString());
        } catch (IOException e) {
            log.error("Problem writing dataset list to output stream", e);
            throw new ServletException("Problem writing JSON to output stream", e);
        }
    }

    private void getTimes(TamsatRequestParams params, HttpServletResponse resp)
            throws ServletException {
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
    }

    private void getData(TamsatRequestParams params, HttpServletResponse resp)
            throws ServletException, FileNotFoundException, IOException {
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
        /*
         * Set as downloaded - this means the file may be removed sooner
         */
        finishedJobState.setDownloaded();

        saveCompletedJobList();
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

        if (!state.success()) {
            log.error("Problem completing job " + state.getId(), state.getError());
        }
        log.debug("Saving completed job " + state.getId());
        addFinishedJob(state);

        saveCompletedJobList();
        saveSubmittedJobList();

        try {
            sendEmail(state.getJobRef());
        } catch (MessagingException e) {
            log.error("Problem sending email", e);
        }
    }
    
    private static final String EMAIL_TITLE = "TAMSAT Data Available";
    private static final String EMAIL_MESSAGE = "Your TAMSAT data is available to download at:\n";

    @SuppressWarnings("restriction")
    private void sendEmail(JobReference jobRef) throws AddressException, MessagingException {
        log.debug("Sending email to "+jobRef.email);

        EmailInfo emailInfo = this.tamsatCatalogue.getEmailInfo();
        
        Security.addProvider(new com.sun.net.ssl.internal.ssl.Provider());
        final String SSL_FACTORY = "javax.net.ssl.SSLSocketFactory";

        Properties props = System.getProperties();
        props.setProperty("mail.smtp.host", emailInfo.getServer());
        props.setProperty("mail.smtp.socketFactory.class", SSL_FACTORY);
        props.setProperty("mail.smtp.port", "465");
        props.setProperty("mail.smtp.auth", "true");
        props.setProperty("mail.smtp.starttls.enable", "true");

        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(emailInfo.getUser(), emailInfo.getPassword());
            }
        });
        
        final MimeMessage msg = new MimeMessage(session);

        msg.setFrom(new InternetAddress(emailInfo.getReplyTo()));
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(jobRef.email, false));

        msg.setSubject(EMAIL_TITLE);
        msg.setText(EMAIL_MESSAGE + "http://hostname:port/path/", "utf-8");
        msg.setSentDate(new Date());

        Transport.send(msg);
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
