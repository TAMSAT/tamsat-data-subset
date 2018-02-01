/*******************************************************************************
 * Copyright (c) 2013 The University of Reading
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
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.xml.bind.JAXBException;

import org.apache.log4j.PropertyConfigurator;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.ac.rdg.resc.edal.catalogue.DataCatalogue;
import uk.ac.rdg.resc.edal.catalogue.SimpleLayerNameMapper;
import uk.ac.rdg.resc.edal.catalogue.jaxb.CatalogueConfig;
import uk.ac.rdg.resc.edal.dataset.DatasetFactory;
import uk.ac.rdg.resc.edal.dataset.cdm.CdmGridDatasetFactory;
import uk.ac.rdg.resc.edal.util.GISUtils.EpsgDatabasePath;

/**
 * The main entry point of the TAMSAT subsetting server. This deals with loading
 * the configuration object and storing it in the ServletContext so that other
 * servlets can use it.
 * 
 * It also deals with any requests which are not specific to subsetting or
 * administration. This is any front-page requests as well as any other
 * information we may want to expose to non-admin users.
 * 
 * This is adapted from the ncWMS2 application servlet
 * 
 * @author Guy Griffiths
 */
public class TamsatApplicationServlet extends HttpServlet {
    public static final String CONTEXT_JOB_LISTING = "JobListing";
    public static final String CONTEXT_CONFIG_DIR = "TamsatConfigDir";
    public static final String CONTEXT_TAMSAT_CATALOGUE = "TamsatCatalogue";
    public static final String CONTEXT_VELOCITY_ENGINE = "VelocityEngine";

    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(TamsatApplicationServlet.class);

    private VelocityEngine velocityEngine;
    private DataCatalogue catalogue;

    @Override
    public void init(ServletConfig servletConfig) throws ServletException {
        super.init(servletConfig);

        CatalogueConfig config;
        /*
         * Set the default dataset factory - will be used when a dataset factory
         * name is not specified
         */
        DatasetFactory.setDefaultDatasetFactoryClass(CdmGridDatasetFactory.class);

        String homeDir = System.getProperty("user.home").replace("\\", "\\\\");
        log.debug("User home directory is: " + homeDir);

        ServletContext context = servletConfig.getServletContext();

        String configDir = context.getInitParameter(CONTEXT_CONFIG_DIR);
        /*
         * By default this is set to $HOME\.tamsat
         */
        if (configDir != null) {
            configDir = configDir.replaceAll("\\$HOME", homeDir);
        }

        /*
         * If we didn't define a config directory (e.g. user deleted it from
         * web.xml)
         */
        if (configDir == null) {
            /*
             * If we can write into the user's home directory, do that,
             * otherwise create the config dir in the system's temporary
             * directory.
             */
            configDir = homeDir + File.separator + ".tamsat";
        }
        
        /*
         * Set the config directory to its actual path, so that other webapps can access it.
         */
        context.setInitParameter(CONTEXT_CONFIG_DIR, configDir);

        log.debug("Config directory is: " + configDir);

        /*
         * If the config location doesn't exist, create it.
         */
        File configDirFile = new File(configDir);
        if (!configDirFile.exists()) {
            boolean success = configDirFile.mkdirs();
            if (!success) {
                configDir = System.getProperty("java.io.tmpdir") + File.separator + ".tamsat";
                configDirFile = new File(configDir);
                if (!configDirFile.mkdirs()) {
                    log.error("Cannot create config dir in home directory or at " + configDir
                            + ".  Please specify the config directory in web.xml or a custom context and restart the webapp.");
                } else {
                    log.warn("Config directory created at " + configDir
                            + ".  This is only a temporary file!  To ensure persistence of settings, please specify the config directory in web.xml or a custom context and restart the webapp.");
                }
            }
        }

        /*
         * We now have the config directory properly set up, now make it
         * available the ServletContext, so that other webapps can use it.
         */
        context.setAttribute(CONTEXT_CONFIG_DIR, configDir);

        log.debug("Config directory: " + configDir);

        /*
         * Set some working directories to the config directory
         */
        EpsgDatabasePath.DB_PATH = configDirFile.getAbsolutePath();
        DatasetFactory.setWorkingDirectory(configDirFile);

        /*
         * If necessary, create a directory for logs.
         */
        File logDirFile = new File(configDir + File.separator + "logs");
        if (!logDirFile.exists()) {
            logDirFile.mkdir();
        }

        /*
         * Get the file appending logger and set the log location
         */
        // Set up the log4j logging system
        Properties logProps = new Properties();
        InputStream log4jInputStream = getClass().getResourceAsStream("/log4j.properties");
        try {
            logProps.load(log4jInputStream);
            logProps.put("log4j.appender.file.File",
                    logDirFile.getPath() + File.separator + "tamsat.log");
            PropertyConfigurator.configure(logProps);
        } catch (IOException e) {
            log.error("Problem setting logging properties", e);
            /*
             * This is a problem, but not a fatal one. Logging will go to its
             * default location.
             */
        }

        /*
         * Now either create or read the TAMSAT config.xml
         */
        File configFile = new File(configDir + File.separator, "config.xml");
        log.debug("Config file is: "+configFile.getAbsolutePath());
        try {
            if (configFile.exists()) {
                log.debug("Reading existing config file");
                config = CatalogueConfig.readFromFile(configFile);
            } else {
                log.debug("No config file - creating new one");
                config = new CatalogueConfig(configFile);
                config.save();
            }
        } catch (JAXBException e) {
            log.error("Config file is invalid - creating new one", e);
            try {
                config = new CatalogueConfig(configFile);
            } catch (Exception e1) {
                throw new ServletException("Old config is invalid, and a new one cannot be created",
                        e1);
            }
        } catch (FileNotFoundException e) {
            /*
             * We shouldn't get here. It means that we've checked that a config
             * file exists and then the FileReader has thrown a
             * FileNotFoundException
             */
            log.error(
                    "Cannot find config file - has it been deleted during startup?  Creating new one",
                    e);
            try {
                config = new CatalogueConfig(configFile);
            } catch (Exception e1) {
                throw new ServletException("Old config is missing, and a new one cannot be created",
                        e1);
            }
        } catch (IOException e) {
            log.error("Problem writing new config file", e);
            throw new ServletException("Cannot create a new config file", e);
        }
        try {
            catalogue = new DataCatalogue(config, new SimpleLayerNameMapper());
        } catch (IOException e) {
            log.error("Problem loading datasets", e);
        }

        /*
         * Store the config in the ServletContext, so that the other servlets
         * can access it. All other servlets are loaded after this one.
         */
        context.setAttribute(CONTEXT_TAMSAT_CATALOGUE, catalogue);

        /*
         * Now create a VelocityEngine to load velocity templates, and make it
         * available to other servlets in the same way
         */
        Properties props = new Properties();
        props.put("resource.loader", "class");
        props.put("class.resource.loader.class",
                "org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader");
        velocityEngine = new VelocityEngine();
        velocityEngine.setProperty(RuntimeConstants.RUNTIME_LOG_LOGSYSTEM_CLASS,
                "org.apache.velocity.runtime.log.Log4JLogChute");
        velocityEngine.setProperty("runtime.log.logsystem.log4j.logger", "velocity");
        velocityEngine.init(props);
        context.setAttribute(CONTEXT_VELOCITY_ENGINE, velocityEngine);
    }

//    @Override
//    protected void doGet(HttpServletRequest req, HttpServletResponse response)
//            throws ServletException, IOException {
//        /* HTTP 1.1 */
//        response.setHeader("Cache-Control", "no-cache");
//        /* HTTP 1.0 */
//        response.setHeader("Pragma", "no-cache");
//        /* Prevents caching at the proxy server */
//        response.setDateHeader("Expires", 0);
//        /*
//         * Just return the front page. If we want some more (dynamic) web pages
//         * available here, we need to do some extra handling of what the URL
//         * actually says
//         */
//        Template template = velocityEngine.getTemplate("templates/index.vm");
//        VelocityContext context = new VelocityContext();
//        EventCartridge ec = new EventCartridge();
//        ec.addEventHandler(new EscapeHtmlReference());
//        ec.attachToContext(context);
//
//        context.put("version", getVersion());
//        context.put("catalogue", catalogue);
//        context.put("config", catalogue.getConfig());
//        context.put("GISUtils", GISUtils.class);
//        context.put("supportedImageFormats", ImageFormat.getSupportedMimeTypes());
//        template.merge(context, response.getWriter());
//    }
//
//    static String getVersion() {
//        String path = "/version.properties";
//        InputStream stream = TamsatApplicationServlet.class.getResourceAsStream(path);
//        if (stream == null)
//            return "UNKNOWN";
//        Properties props = new Properties();
//        try {
//            props.load(stream);
//            stream.close();
//            return (String) props.get("ncwms-version");
//        } catch (IOException e) {
//            return "UNKNOWN";
//        }
//    }
}
