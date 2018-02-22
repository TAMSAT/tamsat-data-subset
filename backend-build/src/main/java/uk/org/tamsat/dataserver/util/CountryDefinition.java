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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.Point;

import uk.ac.rdg.resc.edal.geometry.BoundingBox;
import uk.ac.rdg.resc.edal.geometry.BoundingBoxImpl;
import uk.ac.rdg.resc.edal.position.HorizontalPosition;
import uk.ac.rdg.resc.edal.util.GISUtils;

public class CountryDefinition implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String label;
    private final List<Geometry> regions;

    private GeometryFactory gf = new GeometryFactory();
    private List<BoundingBoxImpl> boxesWithinBounds = new ArrayList<>();

    private final BoundingBox bbox;

    public CountryDefinition(String label, List<Geometry> regions) {
        this.label = label;
        this.regions = regions;
        List<HorizontalPosition> vertices = new ArrayList<>();
        for (Geometry g : regions) {
            Geometry envelope = g.getEnvelope();
            Coordinate[] coordinates = envelope.getCoordinates();
            for (Coordinate c : coordinates) {
                vertices.add(new HorizontalPosition(c.x, c.y));
            }
        }
        this.bbox = GISUtils.getBoundingBox(vertices);
        buildOptimisedBoxes();
    }
    
    public String getLabel() {
        return label;
    }

    public BoundingBox getBoundingBox() {
        return bbox;
    }

    public boolean contains(HorizontalPosition position) {
        for(BoundingBoxImpl bbox : boxesWithinBounds) {
            /*
             * Check our coarse boundary
             */
            if(bbox.contains(position)) {
                return true;
            }
        }
        /*
         * Do a full check using Geometries
         */
        Point point = gf.createPoint(new Coordinate(position.getX(), position.getY()));
        return geometriesContains(point);
    }

    private boolean geometriesContains(Geometry geometry) {
        for (Geometry g : regions) {
            if (g.contains(geometry)) {
                return true;
            }
        }
        return false;
    }

    private void buildOptimisedBoxes() {
        /*
         * This is an optimisation for the contains() method.
         * 
         * For large complex polygons, the contains method can take quite a long
         * time.
         * 
         * Here, we create a 4x4 grid of points and start growing a rectangle
         * from each one. Once the rectangle stops being within any Geometries,
         * we stop growing it.
         * 
         * We then end up with up to 16 rectangles which give a (very very
         * coarse) approximation of the boundary of this polygon. Our contains()
         * method can then check these rectangles first - if a point is within
         * them then it is definitely within this polygon, otherwise a full
         * check needs to be done.
         */
        int n = 5;
        double dx = getBoundingBox().getWidth() / n;
        double dy = getBoundingBox().getHeight() / n;
        
        /*
         * We'll grow each rectangle by 1% of the total bbox width /
         * height at each step.
         */
        double deltaX = getBoundingBox().getWidth() / 100.0;
        double deltaY = getBoundingBox().getHeight() / 100.0;
        
        for (double startX = 0.5 * dx + getBoundingBox().getMinX(); startX < getBoundingBox()
                .getMaxX(); startX += dx) {
            for (double startY = 0.5 * dy + getBoundingBox().getMinY(); startY < getBoundingBox()
                    .getMaxY(); startY += dy) {
                /*
                 * Start with a bounding box with zero width
                 */
                Geometry bbox = gf.toGeometry(new Envelope(startX, startX, startY, startY));
                
                if (geometriesContains(bbox)) {
                    /*
                     * Continue incrementing / decrementing X / Y
                     */
                    boolean incX = true;
                    boolean incY = true;
                    boolean decX = true;
                    boolean decY = true;
                    /*
                     * Upper left coords & width
                     */
                    double minX = startX;
                    double maxX = startX;
                    double minY = startY;
                    double maxY = startY;

                    while (incX || incY || decX || decY) {
                        /*
                         * Test decreasing the minimum X
                         */
                        Geometry newBbox;
                        newBbox = gf.toGeometry(new Envelope(minX - deltaX, maxX, minY, maxY));
                        if (decX && geometriesContains(newBbox)) {
                            minX -= deltaX;
                        } else {
                            decX = false;
                        }
                        /*
                         * Test decreasing the minimum Y
                         */
                        newBbox = gf.toGeometry(new Envelope(minX, maxX, minY - deltaY, maxY));
                        if (decY && geometriesContains(newBbox)) {
                            minY -= deltaY;
                        } else {
                            decY = false;
                        }
                        /*
                         * Test increasing the maximum X
                         */
                        newBbox = gf.toGeometry(new Envelope(minX, maxX + deltaX, minY, maxY));
                        if (incX && geometriesContains(newBbox)) {
                            maxY += deltaX;
                        } else {
                            incX = false;
                        }
                        /*
                         * Test increasing the maximum Y
                         */
                        newBbox = gf.toGeometry(new Envelope(minX, maxX, minY, maxY + deltaY));
                        if (incY && geometriesContains(newBbox)) {
                            maxY += deltaY;
                        } else {
                            incY = false;
                        }
                    }
                    /*
                     * Add the new rectangle to the list of bounds
                     */
                    boxesWithinBounds.add(new BoundingBoxImpl(minX, minY, maxX, maxY));
                }
            }
        }
        /*
         * Sort with largest area rectangles first, since these are most likely
         * to contain a point
         */
        Collections.sort(boxesWithinBounds, new Comparator<BoundingBoxImpl>() {
            @Override
            public int compare(BoundingBoxImpl bb1, BoundingBoxImpl bb2) {
                double area1 = bb1.getWidth() * bb1.getHeight();
                double area2 = bb2.getWidth() * bb2.getHeight();
                if (area1 > area2) {
                    /*
                     * This says that bb1 is "less than" bb2. That's fine
                     * because we want to sort in DESCENDING order.
                     * 
                     * The "proper" thing to do (i.e. such that the Comparator
                     * behaves less confusingly) would be to sort in ascending
                     * order and reverse the list.
                     * 
                     * But we don't need to do that because this Comparator is
                     * only used once and has this massive comment explaining
                     * the situation.
                     */
                    return -1;
                } else if (area1 < area2) {
                    return 1;
                }
                return 0;
            }
        });
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((label == null) ? 0 : label.hashCode());
        result = prime * result + ((regions == null) ? 0 : regions.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        CountryDefinition other = (CountryDefinition) obj;
        if (label == null) {
            if (other.label != null)
                return false;
        } else if (!label.equals(other.label))
            return false;
        if (regions == null) {
            if (other.regions != null)
                return false;
        } else if (!regions.equals(other.regions))
            return false;
        return true;
    }
}
