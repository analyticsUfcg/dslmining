package dsl.job

import java.io.File
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.{Files, Paths}
import java.util.concurrent.CountDownLatch

import api._
import dsl.notification.NotificationEndServer
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{Path, FileUtil, FileSystem}
import org.apache.hadoop.io.{IntWritable, Text}
import org.apache.hadoop.mapred.JobConf
import org.apache.hadoop.mapreduce.lib.input.{SequenceFileInputFormat, TextInputFormat}
import org.apache.hadoop.mapreduce.lib.output.{SequenceFileOutputFormat, TextOutputFormat}
import org.apache.mahout.cf.taste.hadoop.item.VectorAndPrefsWritable
import org.apache.mahout.math.{VarIntWritable, VarLongWritable, VectorWritable}
import org.slf4j.{Logger, LoggerFactory}
import utils.MapReduceUtils

/**
 * Job is a trait that produce results
 */
trait Job {

  val logger : Logger

  var name: String

  var numProcess: Option[Int] = None

  var pathToOutput: Option[String] = None

  var pathToInput = ""

  implicit var aSync = false

  def then(job: Job): Job = {
    //Set the input path of the next job to the output of the current job.
    //Example: a then b ==> b.input = a.output
    job.pathToInput = pathToOutput.getOrElse("") + "/part-r-00000"
    Context.jobs += this
    job
  }

  def afterJob() = {}

  private def after() = {
    this.afterJob()
  }

  // Write on path
  def write_on(path: String) = {
    pathToOutput = Some(path)
    Context.addFinalOutput(path)
    this
  }

  // Execute the Job
  def run() = {
  }

  private def exec() = {
    logger.info(s"Running $name job")
    run()
    after()
  }

  // Run all jobs
  def then(exec: execute.type) = {
    try {
      Context.jobs += this
      var lastOutput = ""
      Context.jobs.foreach(job => {
        if (lastOutput != "")
          job.pathToInput = lastOutput
        job.exec()
        lastOutput = job.pathToOutput.getOrElse("")
      })
    } finally {
      NotificationEndServer.stop
    }
  }

  // Setup the number of nodes
  def in(nodes: Int) = {
    this.numProcess = Some(nodes)
    this
  }
}

object execute


/**
 * Applier is a class that can produce results that should be used immediately
 */
abstract class Applier extends Job

/**
 * Consumer is a class that consumer others produce
 */
abstract class Consumer extends Job

/**
 * ParallelJobs is a class responsible to run other Jobs
 */
class Parallel(val jobs: List[Job]) extends Job {

  def notificationIn(latch: CountDownLatch) = (jobId: String, status: String) => {
    if ("SUCCEEDED" equals status) {
      latch.countDown()
    } else {
      NotificationEndServer.stop
      logger.error(s"Job with id $jobId is finished with status $status. Please check your jobs.")
      System.exit(-1)
    }
  }

  // Run each job
  override def run() = {
    NotificationEndServer.start
    val jobsRunnedCountDown = new CountDownLatch(jobs.size)
    NotificationEndServer addNotificationFunction notificationIn(jobsRunnedCountDown)
    jobs.foreach(job => {
      job.aSync = true
      job.run()
    })

    jobsRunnedCountDown.await
  }

  // Setup the number of nodes
  override def in(nodes: Int) = {
    jobs.foreach(_.in(nodes))
    this
  }

  // Add each job to a queue
  override def then(job: Job) = {
    val newJob = super.then(job)
    job.pathToInput = "data/test3"
    logger.info("running jobs in paralel")
    jobs foreach {
      _.pathToInput = pathToOutput + "/part-r-00000"
    }
    newJob
  }

  override var name: String = "Parallel"
  override val logger = LoggerFactory.getLogger(classOf[Parallel])
}

/**
 * Is a object that can produce a data parse that should be used immediately
 */
object parse_data extends Applier {
  var path = ""

  def clear() = {
    Context.clearQueues()
  }

  // Get a data file
  def on(path: String): Job = {
    clear()
    this.path = path
    name = this.getClass.getSimpleName + s" on $path"
    Context.addInputPath(path)
    this
  }

  override val logger = LoggerFactory.getLogger(this.getClass())
  override var name: String = ""

  // Run the job
  override def run() = {
    super.run()
  }
}


/** Multiplier is class which produce a consumer job.
  * @param producedOne Produce one
  * @param producedTwo Produce two
  */
class Multiplier(val producedOne: Produced, val producedTwo: Produced) extends Consumer {
  override var name: String = this.getClass.getSimpleName + s" $producedOne by $producedTwo"
  override val logger = LoggerFactory.getLogger(classOf[Multiplier])

  // Run the job
  //First Prepare the matrix, then multiply them.
  override def run() = {
    val path1 = Context.paths(producedOne.name)
    val path2 = Context.paths(producedTwo.name)
    super.run()

    val BASE = pathToOutput match {
      case None => Context.basePath
      case Some(path) => path
    }

    //==============================================Prepare the matrices to be multilpied
    val pathToOutput1 = BASE + "/data_prepare"
    PrepareMatrixGenerator.runJob(inputPath1 = path1, inputPath2 = path2, outPutPath = pathToOutput1,
      inputFormatClass = classOf[SequenceFileInputFormat[VarIntWritable, VectorWritable]],
      outputFormatClass = classOf[SequenceFileOutputFormat[VarIntWritable, VectorAndPrefsWritable]],
      deleteFolder = true, numReduceTasks = numProcess)

    //==============================================Multiply the matrices
    pathToInput = pathToOutput1 + "/part-r-00000"
    val pathToOutput2 = BASE + "/data_multiplied"
    pathToOutput = Some(pathToOutput2)

    try{
      MatrixMultiplication.runJob(pathToInput = pathToInput, outPutPath = pathToOutput.get,
        inputFormatClass = classOf[SequenceFileInputFormat[VarIntWritable, VectorWritable]],
        outputFormatClass = classOf[SequenceFileOutputFormat[VarIntWritable, VectorAndPrefsWritable]],
        deleteFolder = true, numReduceTasks = numProcess)
    }catch{ case e:Exception => throw new Exception("Matrix one's columns and Matrix two's lines are not equal")

    }

  }
}

case class WordCount(val input: String, val output: String) extends Job {
  override var name: String = "WordCount"
  override val logger = LoggerFactory.getLogger(classOf[WordCount])

  override def run = {
    super.run
    MapReduceUtils.runJob(jobName = "Prepare", mapperClass = classOf[WordMap],
      reducerClass = classOf[WordReduce], mapOutputKeyClass = classOf[Text],
      mapOutputValueClass = classOf[IntWritable],
      outputKeyClass = classOf[Text], outputValueClass = classOf[IntWritable],
      inputFormatClass = classOf[TextInputFormat],
      outputFormatClass = classOf[TextOutputFormat[Text, IntWritable]],
      inputPath = input, outputPath = output, deleteFolder = true, numMapTasks = numProcess)
  }
}

