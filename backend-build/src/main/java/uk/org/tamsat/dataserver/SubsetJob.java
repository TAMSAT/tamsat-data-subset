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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

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
import uk.ac.rdg.resc.edal.position.HorizontalPosition;
import uk.ac.rdg.resc.edal.util.Array1D;
import uk.ac.rdg.resc.edal.util.Array4D;
import uk.ac.rdg.resc.edal.util.GridCoordinates2D;
import uk.ac.rdg.resc.edal.util.TimeUtils;

public class SubsetJob implements Callable<Integer> {
    public static interface JobFinished {
        public void jobFinished(FinishedJobState state);
    }

    private static final Logger log = LoggerFactory.getLogger(SubsetJob.class);

    private SubsetRequestParams params;
    private GriddedDataset dataset;
    private File dataDir;

    private JobFinished callback;

    public SubsetJob(SubsetRequestParams params, DataCatalogue tamsatCatalogue, File dataDir,
            JobFinished callback) {
        this.params = params;
        Dataset ds = tamsatCatalogue.getDatasetFromId(params.getDatasetId());
        if (!(ds instanceof GriddedDataset)) {
            throw new EdalException("Only gridded datasets may be subset");
        }
        this.dataset = (GriddedDataset) ds;
        this.dataDir = dataDir;
        this.callback = callback;
    }

    @Override
    public Integer call() throws Exception {
        /*
         * TODO - Need to handle crashes in running jobs
         */

        log.debug("Running job " + params.hashCode());
        /*
         * Do the subsetting and save the file
         */
        File outputFile = new File(dataDir, params.getJobId());

        BoundingBox bbox = params.getBbox();
        Set<String> varIds = dataset.getVariableIds();
        if (params.isNetCDF()) {
            /*
             * We want a subset as NetCDF
             * 
             * Subset the feature and write to disk
             */
            GridFeature subset = dataset.subsetFeatures(varIds, bbox, null, params.getTimeRange());
            /*
             * TODO - Do we want the possibility of masking to a country shape?
             * 
             * If so, we need to take that as an argument somehow, then mask out
             * the extra data. For a first version, just do the MBRs client-side
             */
            CdmGridFeatureWrite.gridFeatureToNetCDF(subset, outputFile);
        } else {
            /*
             * We want a timeseries as CSV
             */

            try (BufferedWriter w = new BufferedWriter(new FileWriter(outputFile))) {
                /*
                 * The CSV header will be the same whether this is single-cell
                 * or area-averaged
                 */
                StringBuilder line = new StringBuilder("time,");
                for (String var : varIds) {
                    line.append(var + ",");
                }
                w.write(line.substring(0, line.length() - 1) + "\n");

                /*
                 * Now deal with point / area distinction
                 */
                if (bbox.getWidth() == 0 && bbox.getHeight() == 0) {
                    /*
                     * We want a timeseries at a point, so extract with the
                     * 0-size bounding box.
                     */
                    List<? extends PointSeriesFeature> timeseriesFeatures = dataset
                            .extractTimeseriesFeatures(varIds, bbox, null, params.getTimeRange(),
                                    null, null);
                    /*
                     * This is a timeseries at a point so it should only contain
                     * one feature
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
                                TimeUtils.dateTimeToISO8601(timeAxis.getCoordinateValue(i)) + ",");
                        for (String var : varIds) {
                            line.append(var2Vals.get(var).get(i) + ",");
                        }
                        w.write(line.substring(0, line.length() - 1) + "\n");
                    }
                } else {
                    /*
                     * Subset into a single GridFeature to ensure a common grid,
                     * and the minimum horizontal range (will throw an exception
                     * if not all vars on the same grid, and will take care of
                     * partial overlaps)
                     */
                    GridFeature subset = dataset.subsetFeatures(varIds, bbox, null,
                            params.getTimeRange());

                    HorizontalGrid grid = subset.getDomain().getHorizontalGrid();

                    Map<GridCoordinates2D, Double> weights = getWeightedOverlaps(grid, bbox);

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
                                TimeUtils.dateTimeToISO8601(timeAxis.getCoordinateValue(t)) + ",");

                        /*
                         * For each variable, calculate the area-weighted mean
                         */
                        for (String var : varIds) {
                            double totalVal = 0;
                            double totalWeight = 0;

                            Array4D<Number> vals = var2Vals.get(var);
                            for (int i = 0; i < vals.getXSize(); i++) {
                                for (int j = 0; j < vals.getYSize(); j++) {
                                    GridCoordinates2D gc = new GridCoordinates2D(i, j);
                                    /*
                                     * The weight of the area
                                     */
                                    double weight = weights.get(gc);
                                    Number val = vals.get(t, 0, j, i);
                                    if (val != null) {
                                        totalVal += weight * val.doubleValue();
                                        totalWeight += weight;
                                    }
                                }
                            }
                            line.append(totalVal / totalWeight + ",");
                        }
                        w.write(line.substring(0, line.length() - 1) + "\n");
                    }
                }
            }
        }

        log.debug("Job " + params.hashCode() + " completed");
        /*
         * TODO pick a better output filename
         */
        FinishedJobState finishedJobState = new FinishedJobState(params, outputFile);
        callback.jobFinished(finishedJobState);

        return params.hashCode();
    }

    /**
     * Retrieves all
     * 
     * @param grid
     * @param bbox
     * @return
     */
    private static Map<GridCoordinates2D, Double> getWeightedOverlaps(HorizontalGrid grid,
            BoundingBox bbox) {
        Map<GridCoordinates2D, Double> ret = new HashMap<>();

        grid.getDomainObjects().forEach(new Consumer<GridCell2D>() {
            @Override
            public void accept(GridCell2D cell) {
                if (!(cell.getFootprint() instanceof BoundingBox)) {
                    throw new EdalException("Grid cell is non-rectangular");
                }
                BoundingBox footprint = (BoundingBox) cell.getFootprint();
                boolean hasAll = true;
                boolean hasNone = true;

                for (HorizontalPosition v : footprint.getVertices()) {
                    if (bbox.contains(v)) {
                        hasNone = false;
                    } else {
                        hasAll = false;
                    }
                }
                if (hasAll) {
                    /*
                     * All vertices are within the bounding box
                     */
                    ret.put(cell.getGridCoordinates(), 1.0);
                } else if (!hasNone) {
                    /*
                     * Some vertices are within the bounding box - calculate the
                     * weight.
                     * 
                     * TODO This ignores cases where the bounding box is smaller
                     * in (at least) one dimension than the cell itself. In
                     * practice this is practically guaranteed.
                     */
                    double weight = 1.0;

                    if (footprint.getMinX() < bbox.getMinX()) {
                        weight *= (bbox.getMinX() - footprint.getMinX()) / footprint.getWidth();
                    }
                    if (footprint.getMaxX() > bbox.getMaxX()) {
                        weight *= 1.0
                                - (footprint.getMaxX() - bbox.getMaxX()) / footprint.getWidth();
                    }
                    if (footprint.getMinY() < bbox.getMinY()) {
                        weight *= (bbox.getMinY() - footprint.getMinY()) / footprint.getHeight();
                    }
                    if (footprint.getMaxY() > bbox.getMaxY()) {
                        weight *= 1.0
                                - (footprint.getMaxY() - bbox.getMaxY()) / footprint.getHeight();
                    }
                    ret.put(cell.getGridCoordinates(), weight);
                }
            }
        });

        return ret;
    }
}
