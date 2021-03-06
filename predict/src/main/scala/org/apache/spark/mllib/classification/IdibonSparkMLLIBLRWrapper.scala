package org.apache.spark.mllib.classification

import com.typesafe.scalalogging.StrictLogging
import org.apache.spark.mllib.linalg.BLAS._
import org.apache.spark.mllib.linalg.{SparseVector, DenseVector, Vectors, Vector}

import scala.collection.mutable.ListBuffer

/**
  * Extends an MLLIB LR implementation.
  *
  * Namely it exposes the probability calculation, and significant features.
  *
  * Note: intercept will be zero for 3 or more classes (because the intercept is
  * encoded in the weights).
  */
class IdibonSparkMLLIBLRWrapper(weights: Vector,
                                intercept: Double,
                                numFeatures: Int,
                                numClasses: Int)
  extends LogisticRegressionModel(weights, intercept, numFeatures, numClasses) with StrictLogging {

  private val dataWithBiasSize: Int = weights.size / (numClasses - 1)

  private val weightsArray: Array[Double] = weights match {
    case dv: DenseVector => dv.values
    case _ =>
      throw new IllegalArgumentException(
        s"weights only supports dense vector but got type ${weights.getClass}.")
  }

  /**
    * Method we expose to get at the internals for single vector prediction and
    * returning probabilities for all classes.
    *
    * @param features
    * @return
    */
  def predictProbability(features: Vector): Vector = {
    if(numFeatures != features.size) {
      val delta = features.size - numFeatures
      // delta should always be greater than 0, else FeaturePipeline is wonky.
      assert(delta > 0, s"Expected ${numFeatures} but got ${features.size} which was smaller.")
      logger.trace(s"Predicting with ${delta} OOV dimensions.")
      val sparseVector = features.asInstanceOf[SparseVector]
      val stoppingIndex = {
        val index = sparseVector.indices.indexWhere(_ >= numFeatures)
        if (index > -1) index
        else sparseVector.indices.size
      }
      // can take slice since indices are always are in order of value, and thus new features
      // will always be at the end.
      val modifiedFeatures = Vectors.sparse(
        weights.size,
        sparseVector.indices.slice(0, stoppingIndex),
        sparseVector.values.slice(0, stoppingIndex))
      computeProbabilities(modifiedFeatures)
    } else {
      computeProbabilities(features)
    }
  }

  /**
    * Helper method to compute probabilities of classes given a feature vector.
    * @param features
    * @return
    */
  protected def computeProbabilities(features: Vector): Vector = {
    if (numClasses == 2) {
      val margin = dot(weights, features) + intercept
      val score = 1.0 / (1.0 + math.exp(-margin))
      Vectors.sparse(2, Array(0, 1), Array(1.0 - score, score))
    } else {
      // compute exp(x * w) for each class
      var bestClass = 0
      var maxMargin = 0.0
      val withBias = features.size + 1 == dataWithBiasSize
      val margins = (0 until numClasses - 1).map { i =>
        var margin = 0.0
        // this computes x*w (without intercept)
        features.foreachActive { (index, value) =>
          if (value != 0.0) margin += value * weightsArray((i * dataWithBiasSize) + index)
        }
        // Intercept is required to be added into margin.
        if (withBias) {
          margin += weightsArray((i * dataWithBiasSize) + features.size)
        }
        if (margin > maxMargin) {
          maxMargin = margin
          bestClass = i + 1
        }
        margin  // so this is just the margin
        // FIXME: there is the possibility of overflow that we should handle...
      }.map(margin => math.exp(margin)).toList // exponentiate the value
      val marginSum = margins.sum // get the sum
      val otherClasses = margins.map(m => m / (1 + marginSum))
      val zerothClass = 1 / (1 + marginSum)
      Vectors.sparse(numClasses, (0 until numClasses).toArray, (zerothClass :: otherClasses).toArray)
    }
  }

  /**
    * Returns a list of labelIndex -> List of significant features.
    *
    * @param features
    * @param threshold
    * @return
    */
  def getSignificantDimensions(features: Vector, threshold: Float):
      Seq[(Int, SparseVector)] = {

    // pre-allocate some storage for the returned sparse vectors for each class
    val indices = (0 until numClasses).map(_ => new Array[Int](features.numActives)).toArray
    val probs = (0 until numClasses).map(_ => new Array[Double](features.numActives)).toArray
    val counts = new Array[Int](numClasses)
    val tempVector = Vectors.sparse(features.size, Array(0), Array(1.0)).toSparse

    features.foreachActive((dimension, value) => {
      tempVector.indices(0) = dimension
      val classProbabilities = predictProbability(tempVector)
      classProbabilities.foreachActive((classIndex, probability) => {
        if (probability >= threshold) {
          val count = counts(classIndex)
          indices(classIndex)(count) = dimension
          probs(classIndex)(count) = probability
          counts(classIndex) = count + 1
        }
      })
    })

    counts.zipWithIndex.map({ case (actives, classIndex) => {
      (classIndex, Vectors.sparse(features.size, indices(classIndex).take(actives),
        probs(classIndex).take(actives)).toSparse)
    }})
  }

  /**
    * Helper method to return a vector of indicies that correspond to features used by the model.
    * We cannot just return the weights, since the weight vector indicies cover weights for
    * all labels in the multinomial case. In the binomial case we can just return the weights
    * vector.
    *
    * @return Vector where the indicies represent features used in the model.
    */
  def getFeaturesUsed(): Vector = {
    if (numClasses == 2) {
      assert(numFeatures == weights.numActives, "number of features and weights should match in MLLIB LR model.")
      weights.toSparse
    } else {
      // want to take first numFeatures non-zero entries in dense vector
      val indicies = new Array[Int](numFeatures)
      val values = new Array[Double](numFeatures)
      assert(numFeatures <= dataWithBiasSize, "number of features should be equal to or smaller than nonZero values")
      var i = 0
      var k = 0
      while (i < numFeatures && k < numFeatures) {
        val v = weightsArray(k)
        if (v != 0.0) {
          indicies(i) = k
          values(i) = v
          i += 1
        }
        k += 1
      }
      if (i < numFeatures)
        Vectors.sparse(numFeatures, indicies.slice(0, i), values.slice(0, i))
      else
        Vectors.sparse(numFeatures, indicies, values)
    }
  }
}

/**
  * Static class to house static methods.
  */
object IdibonSparkMLLIBLRWrapper extends StrictLogging {

  /**
    * Creates an IdibonSparkMLLIBLRWrapper object from a MLLIB LR model.
    *
    * @param lrm
    * @return
    */
  def wrap(lrm: LogisticRegressionModel): IdibonSparkMLLIBLRWrapper = {
    new IdibonSparkMLLIBLRWrapper(lrm.weights, lrm.intercept, lrm.numFeatures, lrm.numClasses)
  }
}
