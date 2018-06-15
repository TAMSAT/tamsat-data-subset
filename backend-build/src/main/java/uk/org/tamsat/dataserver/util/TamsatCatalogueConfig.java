/*******************************************************************************
 * Copyright (c) 2018 The University of Reading
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

package uk.org.tamsat.dataserver.util;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

import uk.ac.rdg.resc.edal.catalogue.jaxb.CatalogueConfig;

@XmlRootElement(name = "tamsatConfig")
@XmlAccessorType(XmlAccessType.FIELD)
public class TamsatCatalogueConfig extends CatalogueConfig {
    @XmlElement(name = "email")
    private EmailInfo emailInfo = new EmailInfo();

    /* For JAXB */
    protected TamsatCatalogueConfig() {
    }

    public TamsatCatalogueConfig(File configFile) throws IOException, JAXBException {
        super(configFile);
    }

    public EmailInfo getEmailInfo() {
        return emailInfo;
    }

    public static TamsatCatalogueConfig deserialise(Reader xmlConfig) throws JAXBException {
        JAXBContext context = JAXBContext.newInstance(TamsatCatalogueConfig.class);

        Unmarshaller unmarshaller = context.createUnmarshaller();
        TamsatCatalogueConfig config = (TamsatCatalogueConfig) unmarshaller.unmarshal(xmlConfig);

        return config;
    }

    public static TamsatCatalogueConfig readFromFile(File configFile)
            throws JAXBException, IOException {
        TamsatCatalogueConfig config;
        if (!configFile.exists()) {
            /*
             * If the file doesn't exist, create it with some default values
             */
            //            log.warn("No config file exists in the given location (" + configFile.getAbsolutePath()
            //                    + ").  Creating one with defaults");
            config = new TamsatCatalogueConfig();
            config.configFile = configFile;
            config.save();
        } else {
            /*
             * Otherwise read the file
             */
            config = deserialise(new FileReader(configFile));
            config.configFile = configFile;
        }
        return config;
    }

    @XmlRootElement
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class EmailInfo {
        @XmlElement(name = "smtpHost")
        private String server = null;
        @XmlElement(name = "smtpUser")
        private String user = null;
        @XmlElement(name = "smtpPassword")
        private String password = null;
        @XmlElement(name = "replyTo")
        private String replyTo = null;

        public String getServer() {
            return server;
        }

        public String getUser() {
            return user;
        }

        public String getPassword() {
            return password;
        }

        public String getReplyTo() {
            return replyTo;
        }
    }
}
