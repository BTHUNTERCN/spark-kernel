/*
 * Copyright 2014 IBM Corp.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.ibm.spark.boot.layer

import java.io.File

import com.ibm.spark.comm.{CommManager, KernelCommManager, CommRegistrar, CommStorage}
import com.ibm.spark.dependencies.{DependencyDownloader, IvyDependencyDownloader}
import com.ibm.spark.interpreter._
import com.ibm.spark.kernel.api.Kernel
import com.ibm.spark.kernel.protocol.v5.{ActorLoader, KMBuilder}
import com.ibm.spark.kernel.protocol.v5.stream.KernelMessageStream
import com.ibm.spark.magic.MagicLoader
import com.ibm.spark.magic.builtin.BuiltinLoader
import com.ibm.spark.magic.dependencies.DependencyMap
import com.ibm.spark.utils.{GlobalStreamState, LogLike}
import com.typesafe.config.Config
import org.apache.spark.{SparkContext, SparkConf}

import scala.collection.JavaConverters._

import scala.tools.nsc.Settings
import scala.tools.nsc.interpreter.JPrintWriter

/**
 * Represents the component initialization. All component-related pieces of the
 * kernel (non-actors) should be created here. Limited items should be exposed.
 */
trait ComponentInitialization {
  /**
   * Initializes and registers all components (not needed by bare init).
   *
   * @param config The config used for initialization
   * @param appName The name of the "application" for Spark
   * @param actorLoader The actor loader to use for some initialization
   */
  def initializeComponents(
    config: Config, appName: String, actorLoader: ActorLoader
  ): (CommStorage, CommRegistrar, CommManager, Interpreter, Interpreter,
    Kernel, SparkContext, DependencyDownloader, MagicLoader)
}

/**
 * Represents the standard implementation of ComponentInitialization.
 */
trait StandardComponentInitialization extends ComponentInitialization {
  this: LogLike =>

  /**
   * Initializes and registers all components (not needed by bare init).
   *
   * @param config The config used for initialization
   * @param appName The name of the "application" for Spark
   * @param actorLoader The actor loader to use for some initialization
   */
  def initializeComponents(
    config: Config, appName: String, actorLoader: ActorLoader
  ) = {
    val (commStorage, commRegistrar, commManager) =
      initializeCommObjects(actorLoader)
    val interpreter = initializeInterpreter(config)
    val kernelInterpreter = initializeKernelInterpreter(config, interpreter)
    val kernel = initializeKernel(
      actorLoader, interpreter, kernelInterpreter, commManager)
    val sparkContext = initializeSparkContext(
      config, appName, actorLoader, interpreter)
    val dependencyDownloader = initializeDependencyDownloader(config)
    val magicLoader = initializeMagicLoader(
      config, interpreter, sparkContext, dependencyDownloader)

    (commStorage, commRegistrar, commManager, interpreter, kernelInterpreter,
      kernel, sparkContext, dependencyDownloader, magicLoader)
  }

  private def initializeCommObjects(actorLoader: ActorLoader) = {
    logger.debug("Constructing Comm storage")
    val commStorage = new CommStorage()

    logger.debug("Constructing Comm registrar")
    val commRegistrar = new CommRegistrar(commStorage)

    logger.debug("Constructing Comm manager")
    val commManager = new KernelCommManager(
      actorLoader, KMBuilder(), commRegistrar)

    (commStorage, commRegistrar, commManager)
  }

  private def initializeDependencyDownloader(config: Config) = {
    val dependencyDownloader = new IvyDependencyDownloader(
      "http://repo1.maven.org/maven2/", config.getString("ivy_local")
    )

    dependencyDownloader
  }

  private def initializeInterpreter(config: Config) = {
    val interpreterArgs = config.getStringList("interpreter_args").asScala.toList

    logger.info("Constructing interpreter with arguments: " +
      interpreterArgs.mkString(" "))
    val interpreter = new ScalaInterpreter(interpreterArgs, Console.out)
      with StandardSparkIMainProducer
      with StandardTaskManagerProducer
      with StandardSettingsProducer

    logger.debug("Starting interpreter")
    interpreter.start()

    interpreter
  }

  private def initializeKernelInterpreter(
    config: Config, interpreter: Interpreter
  ) = {
    val interpreterArgs =
      config.getStringList("interpreter_args").asScala.toList

    // TODO: Refactor this construct to a cleaner implementation (for future
    //       multi-interpreter design)
    logger.info("Constructing interpreter with arguments: " +
      interpreterArgs.mkString(" "))
    val kernelInterpreter = new ScalaInterpreter(interpreterArgs, Console.out)
      with StandardTaskManagerProducer
      with StandardSettingsProducer
      with SparkIMainProducerLike {
      override def newSparkIMain(settings: Settings, out: JPrintWriter) = {
        interpreter.asInstanceOf[ScalaInterpreter].sparkIMain
      }
    }
    logger.debug("Starting interpreter")
    kernelInterpreter.start()

    kernelInterpreter
  }

  // TODO: Think of a better way to test without exposing this
  protected[layer] def initializeSparkContext(
    config: Config, appName: String, actorLoader: ActorLoader,
    interpreter: Interpreter
  ) = {
    logger.debug("Creating Spark Configuration")
    val conf = new SparkConf()

    val master = config.getString("spark.master")
    logger.info("Using " + master + " as Spark Master")
    conf.setMaster(master)

    logger.info("Using " + appName + " as Spark application name")
    conf.setAppName(appName)

    // TODO: Add support for spark.executor.uri from environment variable or CLI
    logger.warn("spark.executor.uri is not supported!")
    //conf.set("spark.executor.uri", "...")

    // TODO: Add support for Spark Home from environment variable or CLI
    logger.warn("Spark Home is not supported!")
    //conf.setSparkHome("...")

    // TODO: Move SparkIMain to private and insert in a different way
    logger.warn("Locked to Scala interpreter with SparkIMain until decoupled!")

    // TODO: Construct class server outside of SparkIMain
    logger.warn("Unable to control initialization of REPL class server!")
    logger.info("REPL Class Server Uri: " + interpreter.classServerURI)
    conf.set("spark.repl.class.uri", interpreter.classServerURI)

    val sparkContext = reallyInitializeSparkContext(
      actorLoader, KMBuilder(), conf)

    updateInterpreterWithSparkContext(
      config, sparkContext, interpreter)

    sparkContext
  }

  // TODO: Think of a better way to test without exposing this
  protected[layer] def reallyInitializeSparkContext(
    actorLoader: ActorLoader, kmBuilder: KMBuilder, sparkConf: SparkConf
  ): SparkContext = {
    logger.debug("Constructing new Spark Context")
    // TODO: Inject stream redirect headers in Spark dynamically
    var sparkContext: SparkContext = null
    val outStream = new KernelMessageStream(actorLoader, KMBuilder())
    GlobalStreamState.withStreams(System.in, outStream, outStream) {
      sparkContext = new SparkContext(sparkConf)
    }

    sparkContext
  }

  // TODO: Think of a better way to test without exposing this
  protected[layer] def updateInterpreterWithSparkContext(
    config: Config, sparkContext: SparkContext, interpreter: Interpreter
  ) = {
    interpreter.doQuietly {
      logger.debug("Binding context into interpreter")
      interpreter.bind(
        "sc", "org.apache.spark.SparkContext",
        sparkContext, List( """@transient"""))

      // NOTE: This is needed because interpreter blows up after adding
      //       dependencies to SparkContext and Interpreter before the
      //       cluster has been used... not exactly sure why this is the case
      // TODO: Investigate why the cluster has to be initialized in the kernel
      //       to avoid the kernel's interpreter blowing up (must be done
      //       inside the interpreter)
      logger.debug("Initializing Spark cluster in interpreter")
      interpreter.doQuietly {
        interpreter.interpret("""
          var $toBeNulled = sc.emptyRDD.collect()
          $toBeNulled = null
                              """)
      }
    }

    // Add ourselves as a dependency
    // TODO: Provide ability to point to library as commandline argument
    // TODO: Provide better method to determine if can add ourselves
    // TODO: Avoid duplicating request for master twice (initializeSparkContext
    //       also does this)
    val master = config.getString("spark.master")
    // If in local mode, do not need to add our jar as a dependency
    if (!master.toLowerCase.startsWith("local")) {
      logger.info("Adding self as dependency from " +
        com.ibm.spark.SparkKernel.getClass.getProtectionDomain
          .getCodeSource.getLocation.getPath
      )
      // Assuming inside a jar if not in local mode
      sparkContext.addJar(
        com.ibm.spark.SparkKernel.getClass.getProtectionDomain
          .getCodeSource.getLocation.getPath
      )
    } else {
      logger.info("Running in local mode! Not adding self as dependency!")
    }
  }

  private def initializeKernel(
    actorLoader: ActorLoader,
    interpreterToDoBinding: Interpreter,
    interpreterToBind: Interpreter,
    commManager: CommManager
  ) = {
    //interpreter.doQuietly {
    val kernel = new Kernel(actorLoader, interpreterToBind, commManager)
    interpreterToDoBinding.bind(
      "kernel", "com.ibm.spark.kernel.api.Kernel",
      kernel, List( """@transient implicit""")
    )
    //}

    kernel
  }

  private def initializeMagicLoader(
    config: Config, interpreter: Interpreter, sparkContext: SparkContext,
    dependencyDownloader: DependencyDownloader
  ) = {
    logger.debug("Constructing magic loader")

    logger.debug("Building dependency map")
    val dependencyMap = new DependencyMap()
      .setInterpreter(interpreter)
      .setSparkContext(sparkContext)
      .setDependencyDownloader(dependencyDownloader)

    logger.debug("Creating BuiltinLoader")
    val builtinLoader = new BuiltinLoader()

    val magicUrlArray = config.getStringList("magic_urls").asScala
      .map(s => new File(s).toURI.toURL).toArray

    if (magicUrlArray.isEmpty)
      logger.warn("No external magics provided to MagicLoader!")
    else
      logger.info("Using magics from the following locations: " +
        magicUrlArray.map(_.getPath).mkString(","))

    logger.debug("Creating MagicLoader")
    val magicLoader = new MagicLoader(
      dependencyMap = dependencyMap,
      urls = magicUrlArray,
      parentLoader = builtinLoader
    )

    magicLoader
  }
}
