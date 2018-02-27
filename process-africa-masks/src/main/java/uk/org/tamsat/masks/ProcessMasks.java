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

package uk.org.tamsat.masks;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.geotools.data.DataStore;
import org.geotools.data.DataStoreFinder;
import org.geotools.data.FeatureSource;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.Point;

import ucar.ma2.Array;
import ucar.nc2.constants.AxisType;
import ucar.nc2.dataset.CoordinateAxis;
import ucar.nc2.dataset.NetcdfDataset;

public class ProcessMasks {

    private static final Logger log = LoggerFactory.getLogger(ProcessMasks.class);

    public static void main(String[] args) throws IOException {
        URL resource = ProcessMasks.class.getResource("/shapefiles/Africa.shp");
        Collection<SimpleFeature> features = getFeatures(resource);

        URL url = ProcessMasks.class.getResource("/tamsat_sample.nc");
        String location = url.getPath();
        NetcdfDataset netcdfDataset = NetcdfDataset.openDataset(location);

        CoordinateAxis latAxis = netcdfDataset.findCoordinateAxis(AxisType.Lat);
        CoordinateAxis lonAxis = netcdfDataset.findCoordinateAxis(AxisType.Lon);

        Array latVals = latAxis.read();
        Array lonVals = lonAxis.read();

        GeometryFactory gf = new GeometryFactory();

        Map<String, String> id2Label = new HashMap<>();
        Map<String, Set<int[]>> id2Coords = new HashMap<>();
        Map<String, Envelope> id2Envelope = new HashMap<>();
        for (SimpleFeature f : features) {
            String countryId = getCountryId(f);
            Object caption = f.getAttribute("CAPTION");
            if (caption != null && !caption.toString().isEmpty()) {
                id2Coords.put(countryId, new HashSet<>());
                id2Label.put(countryId, caption.toString());
                log.debug("Got ID: " + countryId + ", for country: " + caption);
            }
            Envelope e1 = ((Geometry) f.getDefaultGeometry()).getEnvelopeInternal();
            if (!id2Envelope.containsKey(countryId)) {
                id2Envelope.put(countryId, e1);
            } else {
                Envelope e2 = id2Envelope.get(countryId);
                Envelope newEnv = new Envelope(Math.min(e1.getMinX(), e2.getMinX()),
                        Math.max(e1.getMaxX(), e2.getMaxX()), Math.min(e1.getMinY(), e2.getMinY()),
                        Math.max(e1.getMaxY(), e2.getMaxY()));
                id2Envelope.put(countryId, newEnv);
            }
        }

        log.debug(
                "Testing all points in TAMSAT grid to see which country (if any) they fall within.  This process will take a long time (2-3 hours as a rough estimate).");
        for (int x = 0; x < lonVals.getSize(); x++) {
            log.debug("Processing column " + x + " of " + lonVals.getSize());
            for (int y = 0; y < latVals.getSize(); y++) {
                Point point = gf
                        .createPoint(new Coordinate(lonVals.getDouble(x), latVals.getDouble(y)));
                for (SimpleFeature f : features) {
                    if (((Geometry) f.getDefaultGeometry()).contains(point)) {
                        String countryId = getCountryId(f);
                        id2Coords.get(countryId).add(new int[] { x, y });
                        break;
                    }
                }
            }
        }

        String outputLocation = args[0];
        if (outputLocation == null || outputLocation.isEmpty()) {
            outputLocation = "output.dat";
        }
        File outFile = new File(outputLocation);
        log.debug("Writing data to file: " + outFile.getAbsolutePath());
        try (BufferedWriter w = new BufferedWriter(new FileWriter(outFile))) {
            for (String country : id2Label.keySet()) {
                Envelope env = id2Envelope.get(country);
                w.write(country + ":" + id2Label.get(country) + ":" + env.getMinX() + ","
                        + env.getMinY() + "," + env.getMaxX() + "," + env.getMaxY() + "\n");
                Set<int[]> coords = id2Coords.get(country);
                for (int[] coord : coords) {
                    w.write(coord[0] + " " + coord[1] + ",");
                }
                w.write("\n");
            }
        } catch (Exception e) {
            log.error("Problem writing data to file", e);
            /*
             * We have failed to write the desired data to disk. Exit with an
             * error condition.
             */
            System.exit(1);
        }
    }

    private static Pattern countryIdPattern = Pattern.compile("([A-Za-z]+)[0-9]*");

    /**
     * Gets the ID of the country from a {@link SimpleFeature}. In the dataset
     * we're using, this is the first 3 letters.
     * 
     * @param feature
     * @return
     */
    private static String getCountryId(SimpleFeature feature) {
        String id = feature.getAttribute("ID").toString();
        Matcher m = countryIdPattern.matcher(id);
        if (!m.matches()) {
            throw new IllegalArgumentException("Feature does not have a correct ID format");
        }
        return m.group(1);
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
}
