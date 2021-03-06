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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import org.opengis.geometry.Geometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.ac.rdg.resc.edal.catalogue.DataCatalogue;
import uk.ac.rdg.resc.edal.dataset.Dataset;
import uk.ac.rdg.resc.edal.dataset.GriddedDataset;
import uk.ac.rdg.resc.edal.dataset.cdm.CdmGridFeatureWrite;
import uk.ac.rdg.resc.edal.exceptions.EdalException;
import uk.ac.rdg.resc.edal.feature.GridFeature;
import uk.ac.rdg.resc.edal.feature.PointSeriesFeature;
import uk.ac.rdg.resc.edal.geometry.BoundingBox;
import uk.ac.rdg.resc.edal.grid.GridCell2D;
import uk.ac.rdg.resc.edal.grid.HorizontalGrid;
import uk.ac.rdg.resc.edal.grid.TimeAxis;
import uk.ac.rdg.resc.edal.util.Array1D;
import uk.ac.rdg.resc.edal.util.Array4D;
import uk.ac.rdg.resc.edal.util.GridCoordinates2D;
import uk.ac.rdg.resc.edal.util.TimeUtils;
import uk.org.tamsat.dataserver.util.CountryDefinition;

public class SubsetJob implements Callable<Integer> {
    public static interface JobFinished {
        public void jobFinished(FinishedJobState state);
    }

    private static final Logger log = LoggerFactory.getLogger(SubsetJob.class);
    private static final DecimalFormat FORMAT_2DP = new DecimalFormat("#.00");

    private final SubsetRequestParams params;
    private final DataCatalogue tamsatCatalogue;
    private final File dataDir;
    private final JobFinished callback;

    public SubsetJob(SubsetRequestParams params, DataCatalogue tamsatCatalogue, File dataDir,
            JobFinished callback) {
        this.params = params;
        this.tamsatCatalogue = tamsatCatalogue;
        this.dataDir = dataDir;
        this.callback = callback;
    }

    @Override
    public Integer call() {
        try {
            /*
             * This job could be submitted before the dataset has been loaded
             * (usually on a reboot)
             * 
             * Wait until it is loaded before running.
             */
            Dataset ds = tamsatCatalogue.getDatasetFromId(params.getDatasetId());
            while (ds == null) {
                log.debug("Dataset " + params.getDatasetId() + " not available yet");
                Thread.sleep(10000L);
                ds = tamsatCatalogue.getDatasetFromId(params.getDatasetId());
            }
            if (!(ds instanceof GriddedDataset)) {
                throw new EdalException("Only gridded datasets may be subset");
            }
            GriddedDataset dataset = (GriddedDataset) ds;

            log.debug("Running job " + params.getJobId());
            /*
             * Do the subsetting and save the file
             */
            File outputFile = new File(dataDir, params.getJobId());

            BoundingBox bbox = params.getBoundingBox();
            Set<String> varIds = dataset.getVariableIds();
            if (params.isNetCDF()) {
                /*
                 * We want a subset as NetCDF
                 * 
                 * Subset the feature and write to disk
                 */
                log.debug("Extracting region");
                GridFeature subset = dataset.subsetFeatures(varIds, bbox, null,
                        params.getTimeRange());
                /*
                 * Now get mask for data which is not part of the requested
                 * Polygon
                 */
                Set<GridCoordinates2D> cellsToMask = null;
                log.debug("Getting masked cells");
                if (params.isCountry()) {
                    cellsToMask = getCellsToMask(subset.getDomain().getHorizontalGrid(),
                            params.getCountryDefinition());
                }
                log.debug("Writing to NetCDF");

                CdmGridFeatureWrite.gridFeatureToNetCDF(subset, outputFile, cellsToMask);
            } else {
                /*
                 * We want a timeseries as CSV
                 */

                try (BufferedWriter w = new BufferedWriter(new FileWriter(outputFile))) {
                    /*
                     * The CSV header will be the same whether this is
                     * single-cell or area-averaged
                     */
                    StringBuilder line = new StringBuilder("time,");
                    for (String var : varIds) {
                        line.append(var + ",");
                    }
                    w.write(line.substring(0, line.length() - 1) + "\n");

                    /*
                     * Now deal with point / area distinction
                     */
                    if (params.isPoint()) {
                        /*
                         * We want a timeseries at a point, so extract with the
                         * 0-size bounding box.
                         */
                        List<? extends PointSeriesFeature> timeseriesFeatures = dataset
                                .extractTimeseriesFeatures(varIds, bbox, null,
                                        params.getTimeRange(), null, null);
                        /*
                         * This is a timeseries at a point so it should only
                         * contain one feature
                         */
                        if (timeseriesFeatures.size() > 1) {
                            throw new EdalException(
                                    "Multiple time series found at a point.  This is an error");
                        }
                        PointSeriesFeature feature = timeseriesFeatures.get(0);

                        /*
                         * Store the value arrays for each variable
                         */
                        Map<String, Array1D<Number>> var2Vals = new HashMap<>();
                        for (String var : varIds) {
                            var2Vals.put(var, feature.getValues(var));
                        }

                        /*
                         * Now write out time series
                         */
                        TimeAxis timeAxis = feature.getDomain();
                        for (int i = 0; i < timeAxis.size(); i++) {
                            line = new StringBuilder(
                                    TimeUtils.formatUtcDateOnly(timeAxis.getCoordinateValue(i))
                                            + ",");
                            for (String var : varIds) {
                                Number value = var2Vals.get(var).get(i);
                                if (value == null || Double.isNaN(value.doubleValue())) {
                                    value = -999;
                                }
                                /*
                                 * We want to format non-integers to 2 d.p.
                                 */
                                if (value instanceof Integer) {
                                    line.append(value + ",");
                                } else {
                                    line.append(FORMAT_2DP.format(value)+",");
                                }
                            }
                            w.write(line.substring(0, line.length() - 1) + "\n");
                        }
                    } else {
                        /*
                         * Subset into a single GridFeature to ensure a common
                         * grid, and the minimum horizontal range (will throw an
                         * exception if not all vars on the same grid, and will
                         * take care of partial overlaps)
                         */
                        GridFeature subset = dataset.subsetFeatures(varIds, bbox, null,
                                params.getTimeRange());

                        HorizontalGrid grid = subset.getDomain().getHorizontalGrid();

                        Set<GridCoordinates2D> cellsToMask = new HashSet<>();
                        if (params.isCountry()) {
                            cellsToMask = getCellsToMask(grid, params.getCountryDefinition());
                        }

                        /*
                         * Store the value arrays for each variable
                         */
                        Map<String, Array4D<Number>> var2Vals = new HashMap<>();
                        for (String var : varIds) {
                            var2Vals.put(var, subset.getValues(var));
                        }

                        /*
                         * Now write out time series
                         */
                        TimeAxis timeAxis = subset.getDomain().getTimeAxis();
                        for (int t = 0; t < timeAxis.size(); t++) {
                            line = new StringBuilder(
                                    TimeUtils.formatUtcDateOnly(timeAxis.getCoordinateValue(t))
                                            + ",");

                            /*
                             * For each variable, calculate the area-weighted
                             * mean
                             */
                            for (String var : varIds) {
                                double totalVal = 0;
                                int totalWeight = 0;

                                Array4D<Number> vals = var2Vals.get(var);
                                for (int i = 0; i < vals.getXSize(); i++) {
                                    for (int j = 0; j < vals.getYSize(); j++) {
                                        GridCoordinates2D gc = new GridCoordinates2D(i, j);
                                        /*
                                         * If this cell is masked, ignore it
                                         */
                                        if (cellsToMask.contains(gc)) {
                                            continue;
                                        }
                                        /*
                                         * Otherwise add it to the count if it
                                         * has a value
                                         */
                                        Number val = vals.get(t, 0, j, i);
                                        if (val != null && !Double.isNaN(val.doubleValue())) {
                                            totalVal += val.doubleValue();
                                            totalWeight++;
                                        }
                                    }
                                }
                                if (!Double.isNaN(totalVal) && totalWeight > 0) {
                                    line.append(FORMAT_2DP.format(totalVal / totalWeight) + ",");
                                } else {
                                    /*
                                     * If we are averaging over somewhere which
                                     * is all missing data (i.e. the totalVal is
                                     * NaN, or more likely, the totalWeight is
                                     * 0), write -999.
                                     */
                                    line.append("-999,");
                                }
                            }
                            w.write(line.substring(0, line.length() - 1) + "\n");
                        }
                    }
                }
            }

            log.debug("Job " + params.getJobId() + " completed");

            FinishedJobState finishedJobState = new FinishedJobState(params, outputFile);
            callback.jobFinished(finishedJobState);

            return params.hashCode();
        } catch (Throwable e) {
            log.error("Problem running job", e);

            log.debug("Job " + params.getJobId() + " finished as failure");
            FinishedJobState failedJobState = new FinishedJobState(params, e);
            callback.jobFinished(failedJobState);

            return params.hashCode();
        }
    }

    /**
     * Finds grid cells whose centres are included in any of the supplied
     * {@link Geometry}s
     * 
     * @param grid
     *            The {@link HorizontalGrid} to calculate weights for
     * @param countryDefinition
     *            A {@link List} of {@link Geometry}s to check inclusion of each
     *            cell
     * @return A {@link Set} of the {@link GridCoordinates2D} which are included
     *         in the given bounds
     */
    private static Set<GridCoordinates2D> getCellsToMask(HorizontalGrid grid,
            CountryDefinition countryDefinition) {
        Set<GridCoordinates2D> ret = new HashSet<>();

        grid.getDomainObjects().forEach(new Consumer<GridCell2D>() {
            @Override
            public void accept(GridCell2D cell) {
                if (!countryDefinition.contains(cell.getGridCoordinates())) {
                    ret.add(cell.getGridCoordinates());
                }
            }
        });

        return ret;
    }
}
