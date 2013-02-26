package org.opentrackingtools.paths;

import gov.sandia.cognition.math.matrix.Vector;
import gov.sandia.cognition.statistics.distribution.MultivariateGaussian;
import gov.sandia.cognition.util.CloneableSerializable;

import org.opentrackingtools.edges.InferredEdge;
import org.opentrackingtools.estimators.AbstractRoadTrackingFilter;
import org.opentrackingtools.model.GpsObservation;
import org.opentrackingtools.model.VehicleState;
import org.opentrackingtools.paths.impl.EdgePredictiveResults;

import com.vividsolutions.jts.geom.Geometry;

public interface PathEdge extends Comparable<PathEdge>, CloneableSerializable {

  public abstract EdgePredictiveResults
      getPredictiveLikelihoodResults(InferredPath path,
        VehicleState state, PathStateBelief beliefPrediction,
        GpsObservation obs);

  public abstract double marginalPredictiveLogLikelihood(
    VehicleState state, InferredPath path,
    MultivariateGaussian beliefPrediction);

  /**
   * Produce an edge-conditional prior predictive distribution. XXX: Requires
   * that belief be in terms of this path.
   * 
   * @param belief
   * @param edge2
   */
  public abstract MultivariateGaussian getPriorPredictive(
    PathStateBelief belief, GpsObservation obs);

  public abstract Double getDistToStartOfEdge();

  public abstract Geometry getGeometry();

  public abstract InferredEdge getInferredEdge();

  public abstract double getLength();

  public abstract Boolean isBackward();

  public abstract boolean isNullEdge();

  /**
   * Based on the path that this edge is contained in, determine if the given
   * distance is on this edge. XXX: It is possible that a given distance is on
   * more than one edge (depends on the value of
   * {@link AbstractRoadTrackingFilter#getEdgeLengthErrorTolerance()}).
   * 
   * @param distance
   * @return
   */
  public abstract boolean isOnEdge(double distance);

  public abstract Vector getCheckedStateOnEdge(Vector mean,
    double edgeLengthErrorTolerance, boolean b);

}