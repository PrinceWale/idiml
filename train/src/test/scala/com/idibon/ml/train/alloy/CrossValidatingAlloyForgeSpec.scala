package com.idibon.ml.train.alloy

import com.idibon.ml.common.EmbeddedEngine
import com.idibon.ml.feature.Buildable
import com.idibon.ml.predict.Classification
import com.idibon.ml.predict.ml.TrainingSummary
import com.idibon.ml.predict.ml.metrics._
import org.scalatest._

/**
  * Tests the Cross Validating Alloy Forge
  */
class CrossValidatingAlloyForgeSpec extends FunSpec
  with Matchers with BeforeAndAfter with ParallelTestExecution with BeforeAndAfterAll {

  val engine = new EmbeddedEngine

  describe("average metrics tests") {
    it("creates averages correctly") {
      val trainingSummaries = Seq[TrainingSummary](
        new TrainingSummary("0", Seq(
          new FloatMetric(MetricTypes.F1, MetricClass.Binary, 1.0f),
          new FloatMetric(MetricTypes.Precision, MetricClass.Binary, 1.0f),
          new FloatMetric(MetricTypes.Recall, MetricClass.Binary, 1.0f)
        )),
        new TrainingSummary("1", Seq(
          new FloatMetric(MetricTypes.F1, MetricClass.Binary, 0.98f),
          new FloatMetric(MetricTypes.Precision, MetricClass.Binary, 0.98f),
          new FloatMetric(MetricTypes.Recall, MetricClass.Binary, 0.98f)
        ))
      )
      val trainer = new CrossValidatingAlloyForge[Classification](
        engine, "test", Seq(), null, 0, 0.0, 1L)
      val Seq(actual) = trainer.averageMetrics("testsummary", trainingSummaries)
      val expected = new TrainingSummary("testsummary", Seq(
        new FloatMetric(MetricTypes.F1, MetricClass.Alloy, 0.99f),
        new FloatMetric(MetricTypes.Precision, MetricClass.Alloy, 0.99f),
        new FloatMetric(MetricTypes.Recall, MetricClass.Alloy, 0.99f)
      ))
      actual.identifier shouldBe expected.identifier
      actual.metrics.sortBy(m => m.metricType) shouldBe expected.metrics.sortBy(m => m.metricType)
    }

    it("creates label confidence deciles correctly when less than 9 values") {
      val trainingSummaries = Seq[TrainingSummary](
        new TrainingSummary("0", Seq(
          new LabelFloatListMetric(MetricTypes.LabelProbabilities, MetricClass.Binary, "x", Seq(
            0.4f, 0.5f, 0.88f, 0.9f
          )),
          new LabelFloatListMetric(MetricTypes.LabelProbabilities, MetricClass.Binary, "y", Seq(
            0.41f, 0.51f, 0.881f, 0.91f
          ))
        )),
        new TrainingSummary("1", Seq(
          new LabelFloatListMetric(MetricTypes.LabelProbabilities, MetricClass.Binary, "x", Seq(
            0.14f, 0.15f, 0.188f, 0.19f
          )),
          new LabelFloatListMetric(MetricTypes.LabelProbabilities, MetricClass.Binary, "y", Seq(
            0.423f, 0.55f, 0.8823f, 0.94f
          ))
        ))
      )
      val trainer = new CrossValidatingAlloyForge[Classification](
        engine, "test", Seq(), null, 0, 0.0, 1L)
      val Seq(actual) = trainer.averageMetrics("testsummary", trainingSummaries)
      val expected = new TrainingSummary("testsummary", Seq[Metric with Buildable[_, _]](
        new LabelPointsMetric(MetricTypes.LabelConfidenceDeciles, MetricClass.Alloy, "x", Seq(
          (1.0f, 0.14f), (2.0f, 0.15f), (3.0f, 0.188f), (4.0f, 0.19f), (5.0f, 0.4f),
          (6.0f, 0.4f), (7.0f, 0.5f), (8.0f, 0.88f), (9.0f, 0.9f)
        )),
          new LabelFloatMetric(MetricTypes.LabelMaxConfidence, MetricClass.Alloy, "x", 0.9f),
          new LabelFloatMetric(MetricTypes.LabelMinConfidence, MetricClass.Alloy, "x", 0.14f),
        new LabelPointsMetric(MetricTypes.LabelConfidenceDeciles, MetricClass.Alloy, "y", Seq(
          (1.0f, 0.41f), (2.0f, 0.423f), (3.0f, 0.51f), (4.0f, 0.55f), (5.0f, 0.881f),
          (6.0f, 0.881f), (7.0f, 0.8823f), (8.0f, 0.91f), (9.0f, 0.94f)
        )),
        new LabelFloatMetric(MetricTypes.LabelMaxConfidence, MetricClass.Alloy, "y", 0.94f),
        new LabelFloatMetric(MetricTypes.LabelMinConfidence, MetricClass.Alloy, "y", 0.41f)
      ))
      actual.identifier shouldBe expected.identifier
      actual.metrics.sortBy(m => m match {
        case m: LabelPointsMetric => (m.label, m.metricType)
        case n: LabelFloatMetric => (n.label, n.metricType)
      }) shouldBe expected.metrics
    }

    it("creates label confidence deciles correctly when we have more than 9 values") {
      val trainingSummaries = Seq[TrainingSummary](
        new TrainingSummary("0", Seq(
          new LabelFloatListMetric(MetricTypes.LabelProbabilities, MetricClass.Binary, "x",
            (0 until 50).map(x => x.toFloat / 100.0f)),
          new LabelFloatListMetric(MetricTypes.LabelProbabilities, MetricClass.Binary, "y",
            (50 until 100).map(x => x.toFloat / 100.0f))
        )),
        new TrainingSummary("1", Seq(
          new LabelFloatListMetric(MetricTypes.LabelProbabilities, MetricClass.Binary, "x",
            (50 until 100).map(x => x.toFloat / 100.0f)),
          new LabelFloatListMetric(MetricTypes.LabelProbabilities, MetricClass.Binary, "y",
            (0 until 50).map(x => x.toFloat / 100.0f))
        ))
      )
      val trainer = new CrossValidatingAlloyForge[Classification](
        engine, "test", Seq(), null, 0, 0.0, 1L)
      val Seq(actual) = trainer.averageMetrics("testsummary", trainingSummaries)
      val expected = new TrainingSummary("testsummary", Seq[Metric with Buildable[_, _]](
        new LabelPointsMetric(MetricTypes.LabelConfidenceDeciles, MetricClass.Alloy, "x",
          (1 to 9).map(i => (i.toFloat, (i.toFloat * 10.0f - 1.0f) / 100.0f ))),
        new LabelFloatMetric(MetricTypes.LabelMaxConfidence, MetricClass.Alloy, "x", 0.99f),
        new LabelFloatMetric(MetricTypes.LabelMinConfidence, MetricClass.Alloy, "x", 0.0f),
        new LabelPointsMetric(MetricTypes.LabelConfidenceDeciles, MetricClass.Alloy, "y",
          (1 to 9).map(i => (i.toFloat, (i.toFloat * 10.0f - 1.0f) / 100.0f ))),
        new LabelFloatMetric(MetricTypes.LabelMaxConfidence, MetricClass.Alloy, "y", 0.99f),
        new LabelFloatMetric(MetricTypes.LabelMinConfidence, MetricClass.Alloy, "y", 0.0f)
      ))
      actual.identifier shouldBe expected.identifier
      actual.metrics.sortBy(m => m match {
        case m: LabelPointsMetric => (m.label, m.metricType)
        case n: LabelFloatMetric => (n.label, n.metricType)
      }) shouldBe expected.metrics
    }
  }
}
