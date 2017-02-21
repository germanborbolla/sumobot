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

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.sumologic.sumobot.brain.InMemoryBrain
import com.sumologic.sumobot.core.Receptionist.{RtmStateRequest, RtmStateResponse}
import com.sumologic.sumobot.core.model.{IncomingMessage, OpenIM}
import com.sumologic.sumobot.plugins.BotPlugin.{InitializePlugin, PluginAdded}
import com.sumologic.sumobot.test.SumoBotSpec
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import slack.api.{BlockingSlackApiClient, RtmStartState, SlackApiClient}
import slack.models._
import slack.rtm.{RtmState, SlackRtmClient}

import scala.concurrent.duration._

class ReceptionistTest
  extends TestKit(ActorSystem("ReceptionistTest"))
  with SumoBotSpec
  with MockitoSugar
  with BeforeAndAfterEach
  with BeforeAndAfterAll {

  private val self = new User("U123", "bender", None, None, None, None, None, None, None, None, None, None, None, None, None, None)
  private val somebodyElse = new User("U124", "dude", None, None, None, None, None, None, None, None, None, None, None, None, None, None)
  private val team = new Team("T123", "testers", "example.com", "example.com", 1, false, null, "no plan")
  private val channel = new Channel("C123", "slack_test", 1, self.id, Some(false), Some(true), Some(false), None, None, None, None, None, None, None, None, None, None, None)
  private val im = new Im("I123", true, somebodyElse.id, 1, None)
  private val startState = new RtmStartState("http://nothing/", self, team, users = List(self, somebodyElse), channels = List(channel), List.empty, ims = List(im), List.empty)

  val state = new RtmState(startState)
  val client = mock[SlackRtmClient]
  val asyncClient = mock[SlackApiClient]
  when(client.state).thenReturn(state)

  private val probe = new TestProbe(system)
  system.eventStream.subscribe(probe.ref, classOf[IncomingMessage])
  private val brain = system.actorOf(Props(classOf[InMemoryBrain]), "brain")
  private val sut = system.actorOf(Props(classOf[Receptionist], client, asyncClient, brain))

  "Receptionist" should {
    "mark messages as addressed to us" when {
      "message starts with @mention" in {
        sut ! new Message(currentTimeStamp, channel.id, somebodyElse.id, s"<@${self.id}> hello dude1", None)
        val result = probe.expectMsgClass(classOf[IncomingMessage])
        result.canonicalText should be("hello dude1")
        result.addressedToUs should be(true)
      }

      "message starts with our name" in {
        sut ! new Message(currentTimeStamp, channel.id, somebodyElse.id, s"${self.name} hello dude2", None)
        val result = probe.expectMsgClass(classOf[IncomingMessage])
        result.canonicalText should be("hello dude2")
        result.addressedToUs should be(true)
      }

      "message is an instant message" in {
        sut ! new Message(currentTimeStamp, im.id, somebodyElse.id, "hello dude3", None)
        val result = probe.expectMsgClass(classOf[IncomingMessage])
        result.canonicalText should be("hello dude3")
        result.addressedToUs should be(true)
      }
    }

    "mark message as not addressed to us otherwise" in {
      sut ! new Message(currentTimeStamp, channel.id, somebodyElse.id, "just a message", None)
      val result = probe.expectMsgClass(classOf[IncomingMessage])
      result.canonicalText should be("just a message")
      result.addressedToUs should be(false)
    }

    "re-interpret messages that were updated" in {

      val previousMessage = new EditMessage(somebodyElse.id, "previous message", currentTimeStamp)
      val newMessage = new EditMessage(somebodyElse.id, "hello dude4", currentTimeStamp)

      sut ! MessageChanged(newMessage, previousMessage, currentTimeStamp, currentTimeStamp, channel.id)
      val result = probe.expectMsgClass(classOf[IncomingMessage])
      result.canonicalText should be("hello dude4")
      result.addressedToUs should be(false)
    }

    "route message when timestamp cannot be parsed" in {
      sut ! new Message("humbug", channel.id, somebodyElse.id, "just a message", None)
      val result = probe.expectMsgClass(classOf[IncomingMessage])
      result.canonicalText should be("just a message")
      result.addressedToUs should be(false)
    }

    "drop a message" when {

      "the time stamp is older than 60 seconds" in {
        val now = System.currentTimeMillis()
        val tooLongAgo = (now - (1000 * 61))/1000
        sut ! new Message(s"$tooLongAgo.000005", im.id, somebodyElse.id, "just a message", None)
        probe.expectNoMsg(1.second)
      }

      "it originated from our user" in {
        sut ! new Message(currentTimeStamp, channel.id, self.id, s"This is me!", None)
        probe.expectNoMsg(1.second)
      }
    }

    "initialize plugins that are added" in {
      sut ! PluginAdded(probe.ref, "")
      val initMessage = probe.expectMsgClass(classOf[InitializePlugin])
      initMessage.brain should be(brain)
      initMessage.state should be(state)
      initMessage.pluginRegistry should not be (null)
    }

    "open a new IM channel asynchronously when asked" in {
      case object DoneWithThat
      sut ! OpenIM(somebodyElse.id, probe.ref, DoneWithThat)
      sut ! ImOpened(somebodyElse.id, im.id)
      probe.expectMsgClass(DoneWithThat.getClass)
    }

    "return the RTM state when asked" in {
      sut ! RtmStateRequest(probe.ref)
      probe.expectMsgClass(classOf[RtmStateResponse])
    }

    "replace html entities with their characters" in {
      sut ! Message(currentTimeStamp, im.id, somebodyElse.id, "&amp; &lt; &gt;", None)
      val result = probe.expectMsgClass(classOf[IncomingMessage])
      result.canonicalText should be("& < >")
      result.addressedToUs should be(true)
    }
  }

  private def currentTimeStamp: String = s"${System.currentTimeMillis()/1000}.000001"

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }
}
