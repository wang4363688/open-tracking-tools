package org.opentrackingtools.graph;

import gov.sandia.cognition.math.UnivariateStatisticsUtil;
import gov.sandia.cognition.math.matrix.Matrix;
import gov.sandia.cognition.math.matrix.Vector;
import gov.sandia.cognition.math.matrix.mtj.DenseMatrix;
import gov.sandia.cognition.statistics.DistributionWithMean;
import gov.sandia.cognition.statistics.distribution.MultivariateGaussian;
import gov.sandia.cognition.util.DefaultPair;
import gov.sandia.cognition.util.Pair;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.geotools.geometry.jts.JTS;
import org.geotools.geometry.jts.JTSFactoryFinder;
import org.geotools.graph.build.line.DirectedLineStringGraphGenerator;
import org.geotools.graph.structure.DirectedEdge;
import org.geotools.graph.structure.DirectedNode;
import org.geotools.graph.structure.Edge;
import org.geotools.graph.structure.Node;
import org.geotools.graph.structure.basic.BasicDirectedEdge;
import org.geotools.graph.structure.basic.BasicDirectedNode;
import org.geotools.graph.traverse.standard.AStarIterator.AStarFunctions;
import org.geotools.graph.traverse.standard.AStarIterator.AStarNode;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;
import org.opentrackingtools.estimators.MotionStateEstimatorPredictor;
import org.opentrackingtools.model.GpsObservation;
import org.opentrackingtools.model.VehicleStateDistribution;
import org.opentrackingtools.paths.Path;
import org.opentrackingtools.paths.PathEdge;
import org.opentrackingtools.util.GeoUtils;
import org.opentrackingtools.util.StatisticsUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.primitives.Doubles;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.LineSegment;
import com.vividsolutions.jts.geom.LineString;
import com.vividsolutions.jts.geom.Point;
import com.vividsolutions.jts.index.strtree.STRtree;
import com.vividsolutions.jts.linearref.LinearLocation;

public class GenericJTSGraph implements InferenceGraph {

  /**
   * Assuming that the LineString is mostly constant allows us to cache values
   * like getLength, which otherwise, over time, build up needless calculations.
   * If the internal values happen to change, then we update the cached values
   * anyway.
   * 
   * @author bwillard
   * 
   */
  public class ConstLineString extends LineString {

    private static final long serialVersionUID = 1114083576711858849L;

    double length;

    public ConstLineString(LineString projectedEdge) {
      super(projectedEdge.getCoordinateSequence(), projectedEdge
          .getFactory());
      this.length = projectedEdge.getLength();
    }

    @Override
    protected void geometryChangedAction() {
      super.geometryChangedAction();
      this.length = super.getLength();
    }

    @Override
    public double getLength() {
      return this.length;
    }
  }

  public class StrictLineStringGraphGenerator extends
      DirectedLineStringGraphGenerator {

    public StrictLineStringGraphGenerator() {
      super();
      this.setGraphBuilder(new StrictDirectedGraphBuilder());
    }
  }

  public static class VehicleStateAStarFunction extends
      AStarFunctions {

    final double obsStdDevDistance;
    final Coordinate toCoord;

    public VehicleStateAStarFunction(Node destination,
      Coordinate toCoord, double obsStdDevDistance) {
      super(destination);
      this.toCoord = toCoord;
      this.obsStdDevDistance = obsStdDevDistance;
    }

    @Override
    public double cost(AStarNode n1, AStarNode n2) {
      final BasicDirectedNode dn1 = (BasicDirectedNode) n1.getNode();
      final BasicDirectedNode dn2 = (BasicDirectedNode) n2.getNode();

      /*
       * TODO are we handling multiple edges correctly?
       */
      final List<Edge> edgesBetween = dn1.getOutEdges(dn2);
      
      /*
       * Make sure this direction is traversable
       */
      if (edgesBetween.isEmpty()) {
        return Double.POSITIVE_INFINITY;
      }

      /*
       * TODO
       * Compute distance past projected value?
       */
      double[] lengths = new double[edgesBetween.size()];
      for (int i = 0; i < lengths.length; i++) {
        lengths[i] = ((LineString)edgesBetween.get(i).getObject()).getLength();
      }

      return Doubles.min(lengths);
    }

    @Override
    public double h(Node n) {

      final double distance =
          ((Point) n.getObject()).getCoordinate().distance(
              this.toCoord);

      if (distance < this.obsStdDevDistance) {
        return 0d;
      }

      return (distance - this.obsStdDevDistance) / 15d; // 15 m/s, ~35 mph, a random driving speed
    }
  }

  protected static final Logger log = LoggerFactory
      .getLogger(GenericJTSGraph.class);

  public static double MAX_DISTANCE_SPEED = 53.6448; // ~120 mph

  /*
   * Maximum radius we're willing to search around a given
   * observation when snapping (for path search destination edges)
   */
  public static double MAX_OBS_SNAP_RADIUS = 200d;
  public static double MIN_OBS_SNAP_RADIUS = 10d;

  /*
   * Maximum radius we're willing to search around a given
   * state when snapping (for path search off -> on-road edges)
   */
  public static double MAX_STATE_SNAP_RADIUS = 350d;

  protected STRtree edgeIndex = null;

  Map<Edge, InferenceGraphEdge> edgeToInfEdge = Maps.newHashMap();
  protected Envelope gpsEnv = null;

  protected DirectedLineStringGraphGenerator graphGenerator = null;

  Map<String, InferenceGraphEdge> idToInfEdge = Maps.newHashMap();

  protected Envelope projEnv = null;

  protected GenericJTSGraph() {
  }

  /**
   * The given collection of lines should be in GPS coordinates, since they will
   * be projected here.
   * 
   * @param lines
   */
  public GenericJTSGraph(Collection<LineString> lines, boolean transformShapesToEuclidean) {
    this.createGraphFromLineStrings(lines, transformShapesToEuclidean);
  }

  protected void createGraphFromLineStrings(
    Collection<LineString> lines, boolean transformShapesToEuclidean) {
    graphGenerator = new StrictLineStringGraphGenerator(); 
    edgeIndex = new STRtree();
    gpsEnv = new Envelope();
    projEnv = new Envelope();
    for (LineString edge : lines) {
      gpsEnv.expandToInclude(edge.getEnvelopeInternal());
      
      Geometry projectedEdge; 
      if (transformShapesToEuclidean) {
        final MathTransform transform = GeoUtils.getTransform(edge.getCoordinate());
        try {
          projectedEdge = JTS.transform(edge, transform);
        } catch (final TransformException e) {
          e.printStackTrace();
          continue;
        }
      } else {
        projectedEdge = edge;
      }
      
      projEnv.expandToInclude(projectedEdge.getEnvelopeInternal());
      final ConstLineString constLine = new ConstLineString((LineString)projectedEdge);
      constLine.setUserData(edge);
      graphGenerator.add(constLine);
    }
    /*
     * Initialize the id map and edge index.
     * 
     * The edge index is build from the line segments of
     * the geoms, so that distance calculations won't 
     * slow things down when querying for nearby edges.
     * 
     * TODO is there some way to do this lazily?  the
     * general problem is that we might want to query
     * an edge by it's id, yet it hasn't been initialized,
     * so it doesn't get into the map (by the way, we
     * have to keep our own map; the internal graph doesn't
     * do that).
     */
    for (Object obj : graphGenerator.getGraph().getEdges()) {
      final BasicDirectedEdge edge = (BasicDirectedEdge) obj;
      InferenceGraphEdge infEdge = getInferenceGraphEdge(edge);
      final LineString lineString = (LineString) infEdge.getGeometry();
      for (LineSegment line : GeoUtils.getSubLineSegments(lineString)) {
        InferenceGraphSegment subline = new InferenceGraphSegment(line, infEdge);
        edgeIndex.insert(new Envelope(line.p0, line.p1), subline);
      }
      
    }
    edgeIndex.build();
  }
  
  @Override
  public InferenceGraphEdge getInferenceGraphEdge(String id) {
    return idToInfEdge.get(id);
  }

  @Override
  public boolean edgeHasReverse(Geometry edge) {
    return this.graphGenerator.get(edge.reverse()) != null;
  }

  @Override
  public Envelope getGPSGraphExtent() {
    return this.gpsEnv;
  }

  @Override
  public Collection<InferenceGraphEdge> getIncomingTransferableEdges(
    InferenceGraphEdge infEdge) {

    final DirectedEdge edge = (DirectedEdge) infEdge.getBackingEdge();
    final Collection<DirectedEdge> inEdges =
        edge.getInNode().getInEdges();

    final Set<InferenceGraphEdge> result = Sets.newHashSet();
    for (final DirectedEdge inEdge : inEdges) {
      result.add(this.getInferenceGraphEdge(inEdge));
    }

    return result;
  }

  private InferenceGraphEdge getInferenceGraphEdge(Edge edge) {
    InferenceGraphEdge infEdge = this.edgeToInfEdge.get(edge);

    if (infEdge == null) {
      final Geometry edgeGeom =
          Preconditions.checkNotNull((Geometry) edge.getObject());
      final int id = edge.getID();
      infEdge = new InferenceGraphEdge(edgeGeom, edge, id, this);

      this.edgeToInfEdge.put(edge, infEdge);
      this.idToInfEdge.put(infEdge.getEdgeId(), infEdge);
    }

    return infEdge;
  }

  @Override
  public Collection<InferenceGraphSegment> getNearbyEdges(Coordinate toCoord,
    double radius) {
    final Envelope toEnv = new Envelope(toCoord);
    toEnv.expandBy(radius);
    final Set<InferenceGraphSegment> streetEdges = Sets.newHashSet();
    for (final Object obj : edgeIndex.query(toEnv)) {
      final InferenceGraphSegment subline = (InferenceGraphSegment) obj;
      Preconditions.checkState(subline.getEndIndex().getSegmentIndex() 
          - subline.getStartIndex().getSegmentIndex() == 1);
      if (subline.getLine().distance(toCoord) < radius)
        streetEdges.add(subline);
      else
        continue;
    }
    return streetEdges;
  }

  @Override
  public Collection<InferenceGraphSegment> getNearbyEdges(
    DistributionWithMean<Vector> initialBelief, Matrix covar) {

    Preconditions.checkArgument(initialBelief.getMean()
        .getDimensionality() == 4);

    final Vector toLoc =
        MotionStateEstimatorPredictor.getOg().times(
            initialBelief.getMean());
    final double varDistance =
        StatisticsUtil.getLargeNormalCovRadius((DenseMatrix) covar);

    return this.getNearbyEdges(toLoc, varDistance);
  }

  @Override
  public Collection<InferenceGraphSegment> getNearbyEdges(
    Vector projLocation, double radius) {
    Preconditions
        .checkArgument(projLocation.getDimensionality() == 2);
    return this.getNearbyEdges(GeoUtils.makeCoordinate(projLocation),
        radius);
  }

  @Override
  public Collection<InferenceGraphEdge> getOutgoingTransferableEdges(
    InferenceGraphEdge infEdge) {

    final DirectedEdge edge = (DirectedEdge) infEdge.getBackingEdge();
    final Collection<DirectedEdge> outEdges =
        edge.getOutNode().getOutEdges();

    final Set<InferenceGraphEdge> result = Sets.newHashSet();
    for (final DirectedEdge outEdge : outEdges) {
      result.add(this.getInferenceGraphEdge(outEdge));
    }

    return result;
  }
  
  /**
   * Finds paths from the given vehicle state's current edge to the edges
   * within a radius around the given observation.  When the given
   * vehicle state is off-road, the edges surrounding the current state are
   * taken as starting points.  Again a search radius is used, and in
   * both cases, the radiuses are proportional to the square root of 
   * Frobenius norms of the state and measurement errors of the given
   * vehicle state. <br>
   * Note: the null path, representing off-road travel to the observation 
   * is always included in the results.
   * 
   */
  @Override
  public Set<Path> getPaths(final VehicleStateDistribution<? extends GpsObservation> fromState,
    final GpsObservation obs) {
    
    Set<Path> paths = Sets.newHashSet();
    paths.add(Path.nullPath);
    
    final Coordinate toCoord = obs.getObsProjected();
    
    PathEdge currrentPathEdge = fromState.getPathStateParam().getValue().getEdge();
    InferenceGraphEdge currentEdge = currrentPathEdge.getInferenceGraphEdge();
    
    Set<InferenceGraphSegment> startEdges = Sets.newHashSet();
    
    if (!currentEdge.isNullEdge()) {
      startEdges.add(currrentPathEdge.getSegment());
    } else {

      MotionStateEstimatorPredictor motionEstimator = 
          Preconditions.checkNotNull(fromState.getMotionStateEstimatorPredictor());
      MultivariateGaussian projectedDist = 
          motionEstimator.createPredictiveDistribution(
            fromState.getMotionStateParam().getParameterPrior());
      MultivariateGaussian obsDist = 
          motionEstimator.getObservationDistribution(projectedDist,
          PathEdge.nullPathEdge);
      final double beliefDistance =
          Math.min(
              StatisticsUtil
                  .getLargeNormalCovRadius((DenseMatrix) obsDist
                      .getCovariance()),
              MAX_STATE_SNAP_RADIUS);

      /*
       * We're short-circuiting the "off->on then move along"
       * path finding.  It causes problems. 
       */
      for (InferenceGraphSegment segment : getNearbyEdges(obsDist.getMean(), beliefDistance)) {
        final Path path = new Path(Collections.singletonList(new PathEdge(segment, 0d, false)), false);
        paths.add(path);
      }
      return paths;
    }

    final double obsCovStdDev = StatisticsUtil
                .getLargeNormalCovRadius(
                    (DenseMatrix) fromState.getObservationCovarianceParam().getValue());
    final double obsStdDevDistance =
        Math.max(MIN_OBS_SNAP_RADIUS, Math.min(obsCovStdDev, MAX_OBS_SNAP_RADIUS));

    final Collection<InferenceGraphSegment> endLines = getNearbyEdges(toCoord,
        obsStdDevDistance);

    if (endLines.isEmpty())
      return paths;
    
    
    for (InferenceGraphSegment startEdge : startEdges) {
      final DirectedEdge bStartEdge = ((DirectedEdge)startEdge.getParentEdge().getBackingEdge());
      final Node source = bStartEdge.getOutNode();
      /*
       * Use this set to avoid recomputing subpaths of 
       * our current paths.
       */
      Set<Node> reachedEndNodes = Sets.newHashSet(source);
      for (InferenceGraphSegment endEdge : endLines) {
        
        if (startEdge.getParentEdge().equals(endEdge.getParentEdge())) {
          final List<PathEdge> currentEdgePathEdges = Lists.newArrayList(); 
          double distance = 0d;
          for (InferenceGraphSegment segment : startEdge.getParentEdge()
              .getSegments(startEdge.startDistance, Double.POSITIVE_INFINITY)) {
            currentEdgePathEdges.add(new PathEdge(segment, distance, false));
            distance += segment.getLine().getLength();
          }
          final Path pathFromStartEdge = new Path(currentEdgePathEdges, false);
          paths.add(pathFromStartEdge);
          continue;
        }
        
      
        final DirectedEdge bEdge = ((DirectedEdge)endEdge.getParentEdge().getBackingEdge());
        List<Node> endNodes = Lists.newArrayList();
        endNodes.add(bEdge.getNodeA());
        endNodes.add(bEdge.getNodeB());
        for (Node target : endNodes) {
          
          if (reachedEndNodes.contains(target))
            continue;
          
          AStarFunctions afuncs = new VehicleStateAStarFunction(target, 
              toCoord, obsStdDevDistance);
          
          CustomAStarShortestPathFinder aStarIter = new CustomAStarShortestPathFinder(
              this.graphGenerator.getGraph(), source, target, afuncs);
          aStarIter.calculate();
          
          org.geotools.graph.path.Path path = aStarIter.getPath();
          
          if (path != null) {
            final Path newPath = getPathFromGraph(path, bStartEdge, 
                startEdge, endEdge, reachedEndNodes);
            if (newPath != null)
              paths.add(newPath);
          }
          // TODO backward paths? 
        }
      }
    }
    
    // TODO debug; remove.
//    if (obs instanceof TrueObservation) {
//      final VehicleState trueState = ((TrueObservation)obs).getTrueState();
//      if (!trueState.getBelief().getPath().isNullPath() && 
//          fromState.getBelief().getEdge().getInferenceGraphEdge()
//            .equals(
//                Iterables.getFirst(trueState.getBelief().getPath().getPathEdges(), null).
//                getInferenceGraphEdge())
//            && Iterables.find(paths, 
//                new Predicate<Path>() {
//
//                  @Override
//                  public boolean apply(Path input) {
//                    return input.getGeometry() != null &&
//                        input.getGeometry().covers(
//                          trueState.getBelief().getPath().getGeometry());
//                  }
//                }
//                , null) == null) {
//        log.warn("True path not found in search results: true=" 
//                + trueState.getBelief().getPath() + ", found=" + paths);
//      }
//    }

    return paths;
  }

  @SuppressWarnings("unchecked")
  protected Path getPathFromGraph(org.geotools.graph.path.Path path, final DirectedEdge bStartEdge, 
    InferenceGraphSegment startSegment, InferenceGraphSegment endSegment, Set<Node> reachedEndNodes) {
    List<PathEdge> pathEdges = Lists.newArrayList();
    
    /*
     * Get only the segments forward from the current segment on the current edge
     */
    double distance = 0d;
    for (InferenceGraphSegment segment : startSegment.getParentEdge().getSegments(
        startSegment.startDistance, Double.POSITIVE_INFINITY)) {
      pathEdges.add(new PathEdge(segment, distance, false));
      distance += segment.getLine().getLength();
    }
    
    Iterator<DirectedNode> rNodes = path.riterator();
    DirectedNode prevNode = rNodes.next();
    while(rNodes.hasNext()) {
      DirectedNode node = rNodes.next();
      Edge outEdge = Preconditions.checkNotNull(prevNode.getOutEdge(node));
      final InferenceGraphEdge infEdge = this.getInferenceGraphEdge(outEdge);
      for (InferenceGraphSegment segment : infEdge.getSegments()) {
        
        Preconditions.checkState(segment.line.p0.equals(
            Iterables.getLast(pathEdges).getLine().p1));
        
        pathEdges.add(new PathEdge(segment, distance, false));
        distance += segment.getLine().getLength();
      }
      reachedEndNodes.add(node);
      prevNode = node;
    }
    if (!pathEdges.isEmpty()) {
      final Path newPath = new Path(pathEdges, false);
      return newPath;
    } else {
      return null;
    }
  }

  
  @Override
  public Envelope getProjGraphExtent() {
    return this.projEnv;
  }

  @Override
  public Set<InferenceGraphEdge> getTopoEquivEdges(
    InferenceGraphEdge edge) {
    final Set<InferenceGraphEdge> result = Sets.newHashSet();
    /*
     * Get reverse edge, if it's there
     */
    final Coordinate[] coords = edge.getGeometry().getCoordinates();
    final Edge revEdge =
        this.graphGenerator.getEdge(coords[coords.length - 1],
            coords[0]);

    final InferenceGraphEdge revInfEdge =
        this.getInferenceGraphEdge(revEdge);
    result.add(edge);
    result.add(revInfEdge);

    return result;
  }

}