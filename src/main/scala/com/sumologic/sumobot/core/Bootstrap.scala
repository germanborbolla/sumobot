/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.sumologic.sumobot.core

import java.io.File

import akka.actor.{ActorRef, ActorSystem, Props}
import com.sumologic.sumobot.plugins.PluginCollection
import com.typesafe.config.ConfigFactory

object Bootstrap {

  private val pluginConfig = ConfigFactory.parseFile(new File("config/sumobot.conf"))

  implicit val system = ActorSystem("sumobot", ConfigFactory.load(pluginConfig))

  var receptionist: Option[ActorRef] = None

  def bootstrap(brainProps: Props,
                pluginCollections: PluginCollection*): Unit = {
    val brain = system.actorOf(brainProps, "brain")
    receptionist = Some(system.actorOf(Props(classOf[Receptionist]), "receptionist"))

    pluginCollections.par.foreach(_.setup)

    sys.addShutdownHook(shutdownActorSystem())
  }

  private def shutdownActorSystem(): Unit = {
    system.shutdown()
    system.awaitTermination()
  }

  def shutdown(): Unit = {
    shutdownActorSystem()
    sys.exit()
  }
}
