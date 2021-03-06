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
package com.sumologic.sumobot.plugins.pagerduty

import net.liftweb.json._
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.DefaultHttpClient

import scala.collection.mutable.ArrayBuffer

class PagerDutySchedulesManager(settings: PagerDutySettings) {
  private[this] val perPage = 100

  def getEscalationPolicies: Option[PagerDutyEscalationPolicies] = {
    var page = 0
    var total = Integer.MAX_VALUE
    val policies = ArrayBuffer[PagerDutyEscalationPolicy]()
    while (page * perPage < total) {
      val (newPolicies, newTotal) = usersForPage(page)
      total = newTotal
      policies ++= newPolicies
      page += 1
    }

    if (policies.isEmpty) {
      None
    } else {
      Some(PagerDutyEscalationPolicies(policies.toList))
    }
  }

  private def usersForPage(page: Int): (Seq[PagerDutyEscalationPolicy], Int) = {
    val str = getPagerDutyJson("escalation_policies/on_call", page * perPage).getOrElse {
      return (Seq.empty, 0)
    }

    val json = JsonParser.parse(str)

    val policies: Seq[PagerDutyEscalationPolicy] = for {
      JArray(policies) <- json \\ "escalation_policies"
      JObject(policy) <- policies
      JField("id", JString(id)) <- policy
      JField("name", JString(name)) <- policy
      JField("on_call", JArray(on_calls)) <- policy
      calls: List[PagerDutyOnCall] = on_calls.map(call => {
        implicit val formats = DefaultFormats.withHints(ShortTypeHints(List(classOf[PagerDutyOnCall],
          classOf[PagerDutyOnCallUser])))

        call.extract[PagerDutyOnCall]

      })
    } yield PagerDutyEscalationPolicy(id, name, calls)

    val JInt(total) = json \ "total"

    (policies, total.intValue())
  }

  private def getPagerDutyJson(command: String, offset: Int): Option[String] = {
    val client = new DefaultHttpClient()
    try {

      val url = s"${settings.url}/api/v1/$command?offset=$offset&limit=$perPage"
      val getReq = new HttpGet(url)

      getReq.addHeader("content-type", "application/json")
      getReq.addHeader("Authorization", s"Token token=${settings.token}")

      val response = client.execute(getReq)
      val entity = response.getEntity


      if (entity != null) {
        val inputStream = entity.getContent
        Some(scala.io.Source.fromInputStream(inputStream).mkString)
      } else {
        None
      }
    } catch {
      case e: Exception =>
        println(e)
        None
    } finally {
      client.getConnectionManager.shutdown()
    }
  }
}
