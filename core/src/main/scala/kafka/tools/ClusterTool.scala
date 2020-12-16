/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.tools

import java.io.PrintStream
import java.util.Properties

import kafka.utils.{Exit, Logging}
import net.sourceforge.argparse4j.ArgumentParsers
import net.sourceforge.argparse4j.impl.Arguments.store
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.common.utils.Utils

object ClusterTool extends Logging {
  def main(args: Array[String]): Unit = {
    try {
      val parser = ArgumentParsers.
        newArgumentParser("kafka-cluster").
        defaultHelp(true).
        description("The Kafka cluster tool.")
      val subparsers = parser.addSubparsers().dest("command")

      val clusterIdParser = subparsers.addParser("cluster-id").
        help("Get information about the ID of a cluster.")
      val decommissionParser = subparsers.addParser("decommission").
        help("Decommission a broker..")
      List(clusterIdParser, decommissionParser).foreach(parser => {
        parser.addArgument("--bootstrap-server", "-b").
          action(store()).
          help("A list of host/port pairs to use for establishing the connection to the kafka cluster.")
        parser.addArgument("--config", "-c").
          action(store()).
          help("A property file containing configs to passed to AdminClient.")
      })
      decommissionParser.addArgument("--id", "-i").
        `type`(classOf[Integer]).
        action(store()).
        help("The ID of the broker to decommission.")

      val namespace = parser.parseArgsOrFail(args)
      val command = namespace.getString("command")
      val properties = Option(Utils.loadProps(namespace.getString("config"))).
        getOrElse(new Properties())
      Option(namespace.getString("bootstrap_server")).
        foreach(b => properties.setProperty("bootstrap.servers", b))
      if (properties.getProperty("bootstrap.servers") == null) {
        throw new TerseFailure("Please specify --bootstrap-server.")
      }

      command match {
        case "cluster-id" =>
          clusterIdCommand(System.out, properties)
          Exit.exit(0)
        case "decommission" =>
          decommissionCommand(System.out, properties, namespace.getInt("id"))
          Exit.exit(0)
        case _ =>
          throw new RuntimeException(s"Unknown command $command")
      }
    } catch {
      case e: TerseFailure =>
        System.err.println(e.getMessage)
        System.exit(1)
    }
  }

  def clusterIdCommand(stream: PrintStream,
                       properties: Properties): Unit = {
    val admin = Admin.create(properties)
    try {
      val clusterId = Option(admin.describeCluster().clusterId().get())
      clusterId match {
        case None => stream.println(s"No cluster ID found. The Kafka version is probably too old.")
        case Some(id) => stream.println(s"Cluster ID: ${id}")
      }
    } finally {
      admin.close()
    }
  }

  def decommissionCommand(stream: PrintStream,
                          properties: Properties,
                          id: Int): Unit = {
//    val admin = Admin.create(properties)
//    try {
//      Option(admin.decommissionBroker(id).all().get())
//    } catch {
//      case e: ExecutionException => {
//        val cause = e.getCause()
//        if (cause.isInstanceOf[UnsupportedVersionException]) {
//          stream.println(s"The target cluster does not support broker decommissioning.")
//        } else {
//          throw e
//        }
//      }
//    } finally {
//      admin.close()
//    }
  }
}
