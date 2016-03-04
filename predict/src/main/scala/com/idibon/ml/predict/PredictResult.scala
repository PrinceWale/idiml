package com.idibon.ml.predict

import com.idibon.ml.alloy.Codec
import com.idibon.ml.feature.{FeatureOutputStream, FeatureInputStream, Builder, Buildable, Feature}

/** Basic output result from a predictive model.
  *
  * Model predictions may return any number of PredictResult objects
  */
trait PredictResult {
  /** The label analyzed in this result */
  def label: String
  /** Evaluated probability */
  def probability: Float
  /** Number of features, rules, etc. considered in analysis */
  def matchCount: Int
  /** Bitmask of PredictResultFlag.Values identified for this result */
  def flags: Int

  /** True if the result is FORCED */
  def isForced = ((flags & (1 << PredictResultFlag.FORCED.id)) != 0)
  def isRule = ((flags & (1 << PredictResultFlag.RULE.id)) != 0)

  /**
    * Method to use to check whether two prediction results are close enough.
    *
    * @param other
    * @return
    */
  def isCloseEnough(other: PredictResult): Boolean = {
    this.label == other.label &&
      this.matchCount == other.matchCount &&
      this.flags == other.flags &&
      this.isForced == other.isForced &&
      this.isRule == other.isRule &&
      PredictResult.floatIsCloseEnough(this.probability, other.probability)
  }
}

object PredictResult {
  /** Our tolerance for numerical instability between machines **/
  val PRECISION = 0.001
  /**
    * Method specifically tasked with figuring out whether two float values
    * are within our tolerances for being "equal".
    *
    * @param thisProb
    * @param otherProb
    * @return
    */
  def floatIsCloseEnough(thisProb: Float, otherProb: Float): Boolean = {
    (thisProb - otherProb).abs < PRECISION
  }
}

/** Flags for special properties affecting the prediction result */
object PredictResultFlag extends Enumeration {
  /** Forced: the result was affected by (e.g.) a blacklist or whitelist rule */
  /** Rule: this is the result of a rule, not an ML model */
  val FORCED,RULE = Value

  /** constant bitmask for no special flags */
  val NO_FLAGS = 0

  /** Compute a bitmask for a list of enabled result flags */
  def mask(flags: PredictResultFlag.Value*): Int = {
    flags.foldLeft(0)((m, f) => m | (1 << f.id))
  }
}

/** Optional trait for PredictResult objects that report significant features */
trait HasSignificantFeatures {
  /** Extracted document features that significantly influence the result */
  def significantFeatures: Seq[(Feature[_], Float)]
}

/** Combines PredictResults for the same label across multiple models into
  * a final result.
  */
trait PredictResultReduction[T <: PredictResult] {

  /** Computes a single representative result from multiple partial results */
  def reduce(components: Seq[T]): T
}

/** Basic PredictResult for document classifications */
case class Classification(override val label: String,
                          override val probability: Float,
                          override val matchCount: Int,
                          override val flags: Int,
                          override val significantFeatures: Seq[(Feature[_], Float)])
  extends PredictResult with HasSignificantFeatures with Buildable[Classification, ClassificationBuilder]{
  /** Stores the data to an output stream so it may be reloaded later.
    *
    * @param output - Output stream to write data
    */
  override def save(output: FeatureOutputStream): Unit = {
    Codec.String.write(output, label)
    output.writeFloat(probability)
    Codec.VLuint.write(output, matchCount)
    Codec.VLuint.write(output, flags)
    Codec.VLuint.write(output, significantFeatures.size)
    significantFeatures.foreach{case (feat, value) => {
      feat match {
        case f: Feature[_] with Buildable[_, _] => {
          output.writeFeature(f)
          output.writeFloat(value)
        }
        case _ => throw new RuntimeException("Got unsaveable feature.")
      }
    }}
  }
}

/**
  * Class for saving classification results to a stream.
  */
class ClassificationBuilder extends Builder[Classification] {
  /** Instantiates and loads an object from an input stream
    *
    * @param input - Data stream where object was previously saved
    */
  override def build(input: FeatureInputStream): Classification = {
    val label = Codec.String.read(input)
    val prob = input.readFloat()
    val matchCount = Codec.VLuint.read(input)
    val flags = Codec.VLuint.read(input)
    val sigFeatSize = Codec.VLuint.read(input)
    val sigFeats = (0 until sigFeatSize).map(_ => {
      val feature = input.readFeature
      val value = input.readFloat()
      (feature, value)
    })
    new Classification(label, prob, matchCount, flags, sigFeats)
  }
}

/** Companion class and reduction operation for Classification instances */
object Classification extends PredictResultReduction[Classification] {
  /** Performs a weighted-average of the partial Classifications
    *
    * If any FORCED results exist, only other results with the FORCED flag
    * are included in the reduced result.
    */
  def reduce(components: Seq[Classification]) = {
    components.filter(_.isForced) match {
      case Nil => {
        // no FORCED entries, just average over all items
        average(components, (c: Classification) => c.matchCount)
      }
      case forced => {
        // FORCED items exist, only average those
        average(forced, (c: Classification) => c.matchCount)
      }
    }
  }
  /** Performs a weighted-average to combine multiple classifications
    *
    * The weighting for each component is provided by a caller-provided
    * method. All partial results must be for the same label.
    */
  def weighted_average(components: Seq[Classification], fn: (Classification) => Int) = {
    val sumMatches = components.foldLeft(0)((sum, c) => sum + fn(c))

    // perform a weighted average of the prediction probability
    val probability = if (sumMatches <= 0) 0.0f else {
      components.foldLeft(0.0f)(
        (sum, c) => sum + fn(c) * c.probability) / sumMatches
    }

    /* return the union of enabled flags and significant features
     * across all partial models */
    Classification(components.head.label, probability, sumMatches,
      components.foldLeft(0)((mask, c) => mask | c.flags),
      components.flatMap(_.significantFeatures))
  }
  /** Calculates the average of multiple classifications grouped by
    * model or rule.
    *
    * The weighted averages of the two groups are calculated, then
    * combined with a harmonic mean to get a true average.
    */
  def average(components: Seq[Classification], fn: (Classification) => Int) = {
    if (components.tail.exists(_.label != components.head.label))
      throw new IllegalArgumentException("can not combine across labels")

    //1. separate the components into rules and not rules
    val models_and_rules = components.groupBy(c => c.isRule).values

    //2. get the averages of each type
    val weighted_probabilities = models_and_rules.map(x => {
      weighted_average(x, fn)
    })

    val n = weighted_probabilities.size

    //all rules, or all ml models
    if (n == 1) {
      Classification(components.head.label, weighted_probabilities.head.probability,
        components.foldLeft(0)((sum, c) => sum + fn(c)),
        components.foldLeft(0)((mask, c) => mask | c.flags),
        components.flatMap(_.significantFeatures))
    } else {
      //harmonic mean of two values = 2*(a*b)/a+b
      val harmonic_mean = 2.0f * divide_or_zero(
        weighted_probabilities.foldLeft(1.0f)((prod, c) => prod * c.probability),
        weighted_probabilities.foldLeft(0.0f)((sum, c) => sum + c.probability))

      Classification(components.head.label, harmonic_mean,
        components.foldLeft(0)((sum, c) => sum + fn(c)),
        components.foldLeft(0)((mask, c) => mask | c.flags),
        components.flatMap(_.significantFeatures))
    }
  }
  /** Divide n/d, return zero if d <= 0 */
  def divide_or_zero(n: Float, d: Float): Float = {
    if (d <= 0) 0.0f else n/d
  }
}
