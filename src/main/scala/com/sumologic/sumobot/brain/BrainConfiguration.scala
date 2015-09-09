package com.sumologic.sumobot.brain

import akka.actor.{Props, ActorSystem}
import com.sumologic.sumobot.core.aws.AWSAccounts

object BrainConfiguration {
  def apply(implicit system: ActorSystem): Props = {
    val config = system.settings.config
    config.getString("brain.type") match {
      case "in-memory" =>
        Props(classOf[InMemoryBrain])
      case "s3" =>
        val bucket = config.getString("brain.bucket")
        val key = config.getString("brain.key")
        val accountName = config.getString("account")
        val credenials = AWSAccounts.load(config).find(_._1 == accountName).
            map(_._2).getOrElse(throw new IllegalArgumentException(s"Could not find AWS account named $accountName"))
        S3Brain.props(credenials, bucket, key)
      case other => throw new IllegalArgumentException(s"Unknown type of brain: $other")
    }
  }
}
