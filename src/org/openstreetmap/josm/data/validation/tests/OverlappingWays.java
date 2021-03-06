// License: GPL. See LICENSE file for details.
package org.openstreetmap.josm.data.validation.tests;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.openstreetmap.josm.command.ChangeNodesCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.OsmUtils;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.osm.WaySegment;
import org.openstreetmap.josm.data.validation.FixableTestError;
import org.openstreetmap.josm.data.validation.Severity;
import org.openstreetmap.josm.data.validation.Test;
import org.openstreetmap.josm.data.validation.TestError;
import org.openstreetmap.josm.gui.progress.ProgressMonitor;
import org.openstreetmap.josm.tools.MultiMap;
import org.openstreetmap.josm.tools.Pair;

/**
 * Tests if there are overlapping ways
 *
 * @author frsantos
 */
public class OverlappingWays extends Test {

    /** Bag of all way segments */
    private MultiMap<Pair<Node,Node>, WaySegment> nodePairs;

    protected static final int OVERLAPPING_HIGHWAY = 101;
    protected static final int OVERLAPPING_RAILWAY = 102;
    protected static final int OVERLAPPING_WAY = 103;
    protected static final int OVERLAPPING_HIGHWAY_AREA = 111;
    protected static final int OVERLAPPING_RAILWAY_AREA = 112;
    protected static final int OVERLAPPING_WAY_AREA = 113;
    protected static final int OVERLAPPING_AREA = 120;
    protected static final int DUPLICATE_WAY_SEGMENT = 121;

    /** Constructor */
    public OverlappingWays() {
        super(tr("Overlapping ways"),
                tr("This test checks that a connection between two nodes "
                        + "is not used by more than one way."));
    }

    @Override
    public void startTest(ProgressMonitor monitor)  {
        super.startTest(monitor);
        nodePairs = new MultiMap<Pair<Node,Node>, WaySegment>(1000);
    }

    private boolean parentMultipolygonConcernsArea(OsmPrimitive p) {
        for (Relation r : OsmPrimitive.getFilteredList(p.getReferrers(), Relation.class)) {
            if (r.concernsArea() ) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void endTest() {
        Map<List<Way>, Set<WaySegment>> seenWays = new HashMap<List<Way>, Set<WaySegment>>(500);

        for (Set<WaySegment> duplicated : nodePairs.values()) {
            int ways = duplicated.size();

            if (ways > 1) {
                List<OsmPrimitive> prims = new ArrayList<OsmPrimitive>();
                List<Way> currentWays = new ArrayList<Way>();
                Collection<WaySegment> highlight;
                int highway = 0;
                int railway = 0;
                int area = 0;

                for (WaySegment ws : duplicated) {
                    if (ws.way.get("highway") != null) {
                        highway++;
                    } else if (ws.way.get("railway") != null) {
                        railway++;
                    }
                    Boolean ar = OsmUtils.getOsmBoolean(ws.way.get("area"));
                    if (ar != null && ar) {
                        area++;
                    }
                    if (ws.way.concernsArea() || parentMultipolygonConcernsArea(ws.way)) {
                        area++;
                        ways--;
                    }

                    prims.add(ws.way);
                    currentWays.add(ws.way);
                }
                /* These ways not seen before
                 * If two or more of the overlapping ways are
                 * highways or railways mark a separate error
                 */
                if ((highlight = seenWays.get(currentWays)) == null) {
                    String errortype;
                    int type;

                    if (area > 0) {
                        if (ways == 0 || duplicated.size() == area) {
                            errortype = tr("Areas share segment");
                            type = OVERLAPPING_AREA;
                        } else if (highway == ways) {
                            errortype = tr("Highways share segment with area");
                            type = OVERLAPPING_HIGHWAY_AREA;
                        } else if (railway == ways) {
                            errortype = tr("Railways share segment with area");
                            type = OVERLAPPING_RAILWAY_AREA;
                        } else {
                            errortype = tr("Ways share segment with area");
                            type = OVERLAPPING_WAY_AREA;
                        }
                    }
                    else if (highway == ways) {
                        errortype = tr("Overlapping highways");
                        type = OVERLAPPING_HIGHWAY;
                    } else if (railway == ways) {
                        errortype = tr("Overlapping railways");
                        type = OVERLAPPING_RAILWAY;
                    } else {
                        errortype = tr("Overlapping ways");
                        type = OVERLAPPING_WAY;
                    }

                    errors.add(new TestError(this,
                            type < OVERLAPPING_HIGHWAY_AREA ? Severity.WARNING : Severity.OTHER,
                                    errortype, type, prims, duplicated));
                    seenWays.put(currentWays, duplicated);
                } else { /* way seen, mark highlight layer only */
                    for (WaySegment ws : duplicated) {
                        highlight.add(ws);
                    }
                }
            }
        }
        super.endTest();
        nodePairs = null;
    }

    public static Command fixDuplicateWaySegment(Way w) {
        // test for ticket #4959
        Set<WaySegment> segments = new TreeSet<WaySegment>(new Comparator<WaySegment>() {
            @Override
            public int compare(WaySegment o1, WaySegment o2) {
                return o1.getFirstNode().compareTo(o2.getFirstNode());
            }
        });
        final Set<Integer> wayNodesToFix = new TreeSet<Integer>(Collections.reverseOrder());
        
        for (int i = 0; i < w.getNodesCount() - 1; i++) {
            final boolean wasInSet = !segments.add(new WaySegment(w, i));
            if (wasInSet) {
                wayNodesToFix.add(i);
            }
        }
        if (wayNodesToFix.size() > 1) {
            final List<Node> newNodes = new ArrayList<Node>(w.getNodes());
            for (final int i : wayNodesToFix) {
                newNodes.remove(i);
            }
            return new ChangeNodesCommand(w, newNodes);
        } else {
            return null;
        }
    }

    @Override
    public void visit(Way w) {

        final Command duplicateWaySegmentFix = fixDuplicateWaySegment(w);
        if (duplicateWaySegmentFix != null) {
            errors.add(new FixableTestError(this, Severity.ERROR, tr("Way contains segment twice"),
                    DUPLICATE_WAY_SEGMENT, w, duplicateWaySegmentFix));
            return;
        }

        Node lastN = null;
        int i = -2;
        for (Node n : w.getNodes()) {
            i++;
            if (lastN == null) {
                lastN = n;
                continue;
            }
            nodePairs.put(Pair.sort(new Pair<Node,Node>(lastN, n)),
                    new WaySegment(w, i));
            lastN = n;
        }
    }
}
