package com.idibon.ml.train.alloy

import scala.concurrent.{Await, TimeoutException}
import scala.concurrent.duration._
import scala.reflect.runtime.universe.typeOf

import com.idibon.ml.predict._
import com.idibon.ml.feature._
import com.idibon.ml.alloy.BaseAlloy
import com.idibon.ml.common.{Engine, EmbeddedEngine}
import com.idibon.ml.train.furnace.Furnace2
import com.idibon.ml.train.TrainOptions

import org.json4s.JObject
import org.json4s.JsonDSL._

import org.scalatest.{Matchers, FunSpec, BeforeAndAfter}

class AlloyTrainer2Spec extends FunSpec with Matchers with BeforeAndAfter {

  before { Furnace2.resetRegistry() }

  it("should train multiple furnaces") {
    Furnace2.register[JunkResult]("JunkFurnace", JunkFurnace)
    val trainerConfig = ("furnaces" -> List(
      (("name" -> "A") ~
       ("furnace" -> "JunkFurnace") ~
       ("config" -> ("delay" -> 0))),
      (("name" -> "B") ~
       ("furnace" -> "JunkFurnace") ~
       ("config" -> ("delay" -> 0)))))
    val trainer = AlloyTrainer2[JunkResult](new EmbeddedEngine,
      "spec", Seq(new Label("00000000-0000-0000-0000-000000000000", "")),
      trainerConfig)
    trainer.furnaces should have length 2

    val options = TrainOptions().build()

    val alloy = Await.result(trainer.train(options), options.maxTrainTime)
    alloy shouldBe a [BaseAlloy[_]]
    alloy.asInstanceOf[BaseAlloy[JunkResult]].models.keys should contain theSameElementsAs Seq("A", "B")
  }

  it("should abort if model training takes too long") {
    Furnace2.register[JunkResult]("JunkFurnace", JunkFurnace)
    val trainerConfig = ("furnaces" -> List(
      (("name" -> "A") ~
       ("furnace" -> "JunkFurnace") ~
       ("config" -> ("delay" -> 500)))))
    val trainer = AlloyTrainer2[JunkResult](new EmbeddedEngine,
      "spec", Seq(new Label("00000000-0000-0000-0000-000000000000", "")),
      trainerConfig)
    val options = TrainOptions().withMaxTrainTime(0.1).build()
    intercept[TimeoutException] {
      Await.result(trainer.train(options), Duration.Inf)
    }
  }
}

class JunkResult extends PredictResult
    with Buildable[JunkResult, JunkResult]
    with Builder[JunkResult] {
  def label = ""
  def probability = 0.0f
  def matchCount = 0
  def flags = 0

  def save(os: FeatureOutputStream) {}
  def build(is: FeatureInputStream) = this
}

class JunkModel extends PredictModel[JunkResult] {
  val reifiedType = classOf[JunkModel]
  def predict(d: Document, p: PredictOptions) = Seq(new JunkResult)
  def getFeaturesUsed = ???
  def getEvaluationMetric = ???
}

class JunkFurnace(val name: String, delay: Int) extends Furnace2[JunkResult] {
  protected def doTrain(options: TrainOptions) = {
    if (delay > 0) Thread.sleep(delay)
    new JunkModel
  }
}

object JunkFurnace extends Function3[Engine, String, JObject, Furnace2[_]] {
  def apply(e: Engine, n: String, c: JObject) = {
    implicit val formats = org.json4s.DefaultFormats
    val delay = (c \ "delay").extract[Int]
    new JunkFurnace(n, delay)
  }
}
