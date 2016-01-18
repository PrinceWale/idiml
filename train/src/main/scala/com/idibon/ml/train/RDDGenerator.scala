package com.idibon.ml.train

import com.idibon.ml.feature.FeaturePipeline

import java.io.File
import com.typesafe.scalalogging.StrictLogging
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.json4s._
import org.json4s.native.JsonMethods.parse
import scala.collection.mutable.{HashMap, ListBuffer}
import scala.io.Source


/** RDDGenerator
  *
  * Produces an RDD of LabeledPoints given a list of documents with annotations. This is intended for use with MLlib
  * for performing logistic regression during training.
  *
  */
class RDDGenerator extends StrictLogging {

  /** Produces an RDD of LabeledPoints for each distinct label name.
    *
    * @param sc: the SparkContext for this application
    * @param filename: a file containing json elements with document contents and their associated annotations
    *                  (post-aggregation). Generated by idibin/bin/open_source_integration/export_training_to_idiml.rb
    *                  Example element:
    *                  { "content":"Who drives a chevy malibu? Would you recommend it?",
    *                    "metadata": {
    *                      "iso_639_1":"en"},
    *                    "annotations": [{
    *                      "label": {
    *                        "name":"Intent to Buy"},
    *                      "isPositive":true}]}
    * @param pipeline: a FeaturePipeline that has been instantiated and is ready to run
    * @return an RDD of LabeledPoints corresponding to the given documents and annotations. Feature vectors are
    *         obtained from the provided pipeline.
    */
  def getLabeledPointRDDs(sc: SparkContext, filename: String,
                          pipeline: FeaturePipeline) : Option[HashMap[String, RDD[LabeledPoint]]] = {
    logger.info(s"Creating Labeled Points from ${filename}.")
    if (!new File(filename).exists()) return None
    // Prime the index by reading each document from the input file, which assigns an index value to each token
    val linesInFile = Source.fromFile(filename).getLines()
    for (line <- linesInFile) {
      val json = parse(line)
      pipeline(json.asInstanceOf[JObject])
    }
    implicit val formats = org.json4s.DefaultFormats
    // Iterate over the data one more time now that the index is complete. This ensures that every feature vector
    // will now be the same size
    val perLabelLPs = HashMap[String, ListBuffer[LabeledPoint]]()
    for (line <- Source.fromFile(filename).getLines()) {
      logger.debug(line)
      // Extract the label name and its sign (positive or negative)
      val json = parse(line)
      //TODO(Michelle): handle case with multiple positive annotations.
      val annotations = (json \ "annotations").extract[JArray]
      val first_entry = annotations.apply(0)
      val JString(label) = first_entry \ "label" \ "name"
      val JBool(isPositive) = first_entry \ "isPositive"

      // If we haven't seen this label before, instantiate a list
      if (!perLabelLPs.contains(label)) {
        perLabelLPs(label) = new ListBuffer[LabeledPoint]()
      }

      // Assign a number that MLlib understands
      val labelNumeric = isPositive match {
        case true => 1.0
        case false => 0.0
      }

      // Run the pipeline to generate the feature vector
      val featureVector = pipeline(json.asInstanceOf[JObject]).head

      // Create labeled points
      perLabelLPs(label) += LabeledPoint(labelNumeric, featureVector)
    }

    // Generate the RDDs, given the per-label list of LabeledPoints we just created
    val perLabelRDDs = HashMap[String, RDD[LabeledPoint]]()
    for ((label, lp) <- perLabelLPs) {
      perLabelRDDs(label) = sc.parallelize(lp)
      logger.info(s"Created ${lp.size} data points for ${label}.")
    }

    Some(perLabelRDDs)
  }
}
