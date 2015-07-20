package com.sumologic.sumobot.plugins.brain

import akka.pattern._
import akka.util.Timeout
import com.sumologic.sumobot.brain.Brain.{ListValues, Remove, Store, ValueMap}
import com.sumologic.sumobot.core.IncomingMessage
import com.sumologic.sumobot.plugins.BotPlugin

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class BrainSurgery extends BotPlugin {

  override protected def help =
    """Mess with my brain:
      |
      |dump your brain - I'll tell you everything I know.
      |remember: xxx.yyy=zzz - Will make me remember.
      |forget about xxx.yyy - Will make me forget xxx.
    """.stripMargin

  private val brainDump = matchText(".*dump\\s.*brain.*")

  private val remember = matchText("remember[\\s\\:]+([\\.\\w]+)=(\\w+).*")

  private val forget = matchText("forget about ([\\.\\w]+).*")

  override protected def receiveIncomingMessage = {
    case message@IncomingMessage(remember(key, value), true, _) =>
      brain ! Store(key.trim, value.trim)
      message.respond(s"Got it, $key is $value")
    case message@IncomingMessage(forget(key), true, _) =>
      brain ! Remove(key.trim)
      message.respond(s"$key? I've forgotten all about it.")
    case message@IncomingMessage(brainDump(), true, _)  =>
      implicit val timeout = Timeout(5.seconds)
      (brain ? ListValues()) map {
        case ValueMap(map) =>
          if (map.isEmpty) {
            message.say("My brain is empty.")
          } else {
            message.say(map.toSeq.sortBy(_._1).map(tpl => s"${tpl._1}=${tpl._2}").mkString("\n"))
          }
      }
  }
}
