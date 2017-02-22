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

import akka.actor._
import com.sumologic.sumobot.core.Receptionist.{RtmStateRequest, RtmStateResponse}
import com.sumologic.sumobot.core.model._
import com.sumologic.sumobot.plugins.BotPlugin.{InitializePlugin, PluginAdded, PluginRemoved}
import org.apache.commons.lang.StringEscapeUtils
import slack.api.{BlockingSlackApiClient, SlackApiClient}
import slack.models.{ImOpened, Message, MessageChanged}
import slack.rtm.{RtmState, SlackRtmClient}

import scala.concurrent.ExecutionContext.Implicits.global

object Receptionist {

  case class RtmStateRequest(sendTo: ActorRef)

  case class RtmStateResponse(rtmState: RtmState)

  def props(rtmClient: SlackRtmClient, asyncClient: SlackApiClient, brain: ActorRef): Props =
    Props(classOf[Receptionist], rtmClient, asyncClient, brain)
}

class Receptionist(rtmClient: SlackRtmClient, asyncClient: SlackApiClient, brain: ActorRef) extends Actor with ActorLogging {

  private val selfId = rtmClient.state.self.id
  private val selfName = rtmClient.state.self.name
  rtmClient.addEventListener(self)

  private val atMention = """<@(\w+)>:(.*)""".r
  private val atMentionWithoutColon = """<@(\w+)>\s(.*)""".r
  private val simpleNamePrefix = """(\w+)\:?\s(.*)""".r
  private val tsPattern = "(\\d+)\\.(\\d+)".r

  private val messageAgeLimitMillis = 60 * 1000

  private var pendingIMSessionsByUserId = Map[String, (ActorRef, AnyRef)]()

  private val pluginRegistry = context.system.actorOf(Props(classOf[PluginRegistry]), "plugin-registry")

  override def preStart(): Unit = {
    context.system.eventStream.subscribe(self, classOf[OutgoingMessage])
    context.system.eventStream.subscribe(self, classOf[OpenIM])
    context.system.eventStream.subscribe(self, classOf[RtmStateRequest])
  }


  override def postStop(): Unit = {
    context.system.eventStream.unsubscribe(self)
  }

  override def receive: Receive = {

    case message@PluginAdded(plugin, _) =>
      plugin ! InitializePlugin(rtmClient.state, brain, pluginRegistry)
      pluginRegistry ! message

    case message@PluginRemoved(_) =>
      pluginRegistry ! message

    case OutgoingMessage(channel, text, thread) =>
      log.info(s"sending - ${channel.name}: $text")
      rtmClient.sendMessage(channel.id, text, thread)

    case ImOpened(user, channel) =>
      pendingIMSessionsByUserId.get(user).foreach {
        tpl =>
          tpl._1 ! tpl._2
          pendingIMSessionsByUserId = pendingIMSessionsByUserId - user
      }

    case OpenIM(userId, doneRecipient, doneMessage) =>
      asyncClient.openIm(userId)(context.system)
      pendingIMSessionsByUserId = pendingIMSessionsByUserId + (userId ->(doneRecipient, doneMessage))

    case message: Message if !tooOld(message.ts, message) =>
      translateAndDispatch(message.channel, message.user, message.text, message.ts, message.thread_ts)

    case messageChanged: MessageChanged if !tooOld(messageChanged.ts, messageChanged) =>
      val message = messageChanged.message
      // TODO(panda, 2017-02-21): support threads for edited messages
      translateAndDispatch(messageChanged.channel, message.user, message.text, message.ts, None)

    case RtmStateRequest(sendTo) =>
      sendTo ! RtmStateResponse(rtmClient.state)
  }

  protected def translateMessage(channelId: String, userId: String, incomingText: String, ts: String, thread_ts: Option[String]): IncomingMessage = {

    val channel = Channel.forChannelId(rtmClient.state, channelId)
    val sentByUser = rtmClient.state.users.find(_.id == userId).
      getOrElse(throw new IllegalStateException(s"Message from unknown user: $userId"))

    StringEscapeUtils.unescapeHtml(incomingText) match {
      case atMention(user, text) if user == selfId =>
        IncomingMessage(text.trim, true, channel, sentByUser, ts, thread_ts)
      case atMentionWithoutColon(user, text) if user == selfId =>
        IncomingMessage(text.trim, true, channel, sentByUser, ts, thread_ts)
      case simpleNamePrefix(name, text) if name.equalsIgnoreCase(selfName) =>
        IncomingMessage(text.trim, true, channel, sentByUser, ts, thread_ts)
      case x =>
        IncomingMessage(x.trim, channel.isInstanceOf[InstantMessageChannel], channel, sentByUser, ts, thread_ts)
    }
  }

  private def translateAndDispatch(channelId: String, userId: String, text: String, ts: String, thread_ts: Option[String]): Unit = {
    val msgToBot = translateMessage(channelId, userId, text, ts, thread_ts)
    if (userId != selfId) {
      log.info(s"Dispatching message: $msgToBot")
      context.system.eventStream.publish(msgToBot)
    }
  }

  // NOTE(stefan, 2017-01-09): This check is required because I've seen the API send us some old messages
  // at times that I can't explain to myself.
  private def tooOld(ts: String, message: AnyRef): Boolean = {
    ts match {
      case tsPattern(timeOfMessage, _) =>
        val oldestAllowableMessageTime = (System.currentTimeMillis() - messageAgeLimitMillis)/1000
        val messageTooOld = timeOfMessage.toLong < oldestAllowableMessageTime
        if (messageTooOld) {
          log.warning(s"Discarding old message ($ts is too old, cutoff is $oldestAllowableMessageTime): $message")
        }
        messageTooOld
      case other: String =>
        log.warning(s"Could not parse ts: '$other'")
        false
    }
  }
}
