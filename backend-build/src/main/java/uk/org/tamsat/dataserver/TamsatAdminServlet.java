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

import java.io.IOException;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.app.event.EventCartridge;
import org.apache.velocity.app.event.implement.EscapeHtmlReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.org.tamsat.dataserver.util.JobListing;

/**
 * An {@link HttpServlet} which deals with the admin pages of TAMSAT data
 * subset. Currently just lists all jobs.
 *
 * @author Guy Griffiths
 */
public class TamsatAdminServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(TamsatAdminServlet.class);

    private JobListing jobListing;
    private VelocityEngine velocityEngine;

    public TamsatAdminServlet() throws IOException, Exception {
        super();
    }

    @Override
    public void init(ServletConfig servletConfig) throws ServletException {
        super.init(servletConfig);

        /*
         * Retrieve the pre-loaded velocity engine
         */
        Object jobList = servletConfig.getServletContext()
                .getAttribute(TamsatApplicationServlet.CONTEXT_JOB_LISTING);
        if (jobList instanceof JobListing) {
            jobListing = (JobListing) jobList;
        } else {
            log.error("Admin servlet does not have access to the available jobs.");
            throw new ServletException("Admin servlet does not have access to the available jobs.  This is a bug.");
        }
        
        /*
         * Retrieve the pre-loaded velocity engine
         */
        Object engine = servletConfig.getServletContext()
                .getAttribute(TamsatApplicationServlet.CONTEXT_VELOCITY_ENGINE);
        if (engine instanceof VelocityEngine) {
            velocityEngine = (VelocityEngine) engine;
        } else {
            throw new ServletException("VelocityEngine object is incorrect type.  The \""
                    + TamsatApplicationServlet.CONTEXT_VELOCITY_ENGINE
                    + "\" attribute of the ServletContext has been incorrectly set.");
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        /*
         * Don't cache admin requests - they are all dynamically generated
         */
        /* HTTP 1.1 */
        response.setHeader("Cache-Control", "no-cache");
        /* HTTP 1.0 */
        response.setHeader("Pragma", "no-cache");
        /* Prevents caching at the proxy server */
        response.setDateHeader("Expires", 0);

        /*
         * Parse the request
         */
        String path = request.getPathInfo();

        /*
         * Redirect URLs of the form http://hostname/servlet/admin to
         * http://hostname/servlet/admin/
         */
        if (path == null) {
            response.sendRedirect(request.getRequestURI() + "/");
            return;
        }

        /*
         * Currently we only want to return 
         */
        if ("/".equals(path)) {
            Template template = velocityEngine.getTemplate("templates/admin.vm");
            VelocityContext context = new VelocityContext();
            EventCartridge ec = new EventCartridge();
            ec.addEventHandler(new EscapeHtmlReference());
            ec.attachToContext(context);

            context.put("queuedJobs", jobListing.getQueuedJobs());
            context.put("finishedJobs", jobListing.getFinishedJobs());
            try {
                template.merge(context, response.getWriter());
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
    }
}
