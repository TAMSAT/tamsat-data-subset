package uk.org.tamsat.dataserver;
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

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.xml.bind.JAXBException;

import uk.ac.rdg.resc.edal.catalogue.DataCatalogue;
import uk.ac.rdg.resc.edal.catalogue.jaxb.CatalogueConfig;
import uk.ac.rdg.resc.edal.dataset.DatasetFactory;
import uk.ac.rdg.resc.edal.dataset.cdm.CdmGridDatasetFactory;
import uk.ac.rdg.resc.edal.graphics.utils.SimpleLayerNameMapper;
import uk.org.tamsat.dataserver.SubsetJob.JobFinished;
import uk.org.tamsat.dataserver.util.CountryDefinition;

/**
 * A class which subsets every available country for test purposes
 *
 * @author Guy Griffiths
 */
public class DataSubsetTest {

    public static void main(String[] args) throws IOException, JAXBException {
        URL africaMasks = DataSubsetTest.class.getResource("/africa_masks.dat");
        Map<String, CountryDefinition> masks = TamsatDataSubsetServlet
                .loadCountryMasks(africaMasks);

        DatasetFactory.setDefaultDatasetFactoryClass(CdmGridDatasetFactory.class);
        File configFile = new File("/home/guy/.tamsat/config.xml");
        CatalogueConfig config = CatalogueConfig.readFromFile(configFile);
        DataCatalogue catalogue = new DataCatalogue(config, new SimpleLayerNameMapper());

        Map<String, String[]> paramMap = new HashMap<>();
        paramMap.put("DATASET", new String[] { "tamsat" });
        paramMap.put("DATATYPE", new String[] { "netcdf" });
        paramMap.put("STARTTIME", new String[] { "2017-01-01T00:00:00.000Z" });
        paramMap.put("ENDTIME", new String[] { "2017-02-01T00:00:00.000Z" });
        paramMap.put("EMAIL", new String[] { "wh@t.ever" });
        paramMap.put("REF", new String[] { "abc123" });

        ExecutorService queue = Executors.newFixedThreadPool(6);
        for (String country : masks.keySet()) {
            paramMap.put("COUNTRY", new String[] { country });
            TamsatRequestParams tamsatParams = new TamsatRequestParams(paramMap);
            SubsetRequestParams params = new SubsetRequestParams(tamsatParams, masks, "");
            SubsetJob subsetJob = new SubsetJob(params, catalogue,
                    new File("/home/guy/temp/subsets"), new JobFinished() {
                        @Override
                        public void jobFinished(FinishedJobState state) {
                            System.out.println(
                                    "Job for file " + state.getOutputFilename() + " completed");
                        }
                    });
            queue.submit(subsetJob);
        }
    }
}
