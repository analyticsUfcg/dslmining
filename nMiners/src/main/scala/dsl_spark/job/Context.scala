package dsl_spark.job

import scala.collection.mutable.HashSet
import scala.collection.mutable.Queue
import scala.reflect.ClassTag

object Context {

  val INPUT_PATH_KEY: String = "INPUT_PATH"
  val OUTPUT_PATH_KEY: String = "OUTPUT_PATH"

  var basePath: String = ""

  val jobs = new Queue[Job]()

  val produceds = new HashSet[Produced[_]]()

  def producedsByType[T <: Producer[_]](implicit tag: ClassTag[T]): Option[T] = produceds.find { p => p.producer match {
    case e => tag.runtimeClass equals e.getClass
  }
  }.map(_.producer.asInstanceOf[T])

  def clearQueues() = {
    jobs.clear()
    produceds.clear()
  }
}
