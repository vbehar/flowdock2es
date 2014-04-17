/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package flowdock2es

import scala.collection.JavaConversions._
import scala.collection.mutable.Map
import scala.util.Try

import dispatch._
import dispatch.Defaults._

import net.liftweb.json._
import net.liftweb.json.JsonDSL._

import org.joda.time.format.ISODateTimeFormat
import org.slf4j.LoggerFactory
import org.elasticsearch.common.settings._

import com.sksamuel.elastic4s._
import com.sksamuel.elastic4s.ElasticDsl._
import com.sksamuel.elastic4s.mapping.FieldType._
import com.sksamuel.elastic4s.source.DocumentMap

import com.typesafe.config.{ ConfigFactory, ConfigException, Config }

/**
 * Load messages from Flowdock API, and index them in Elasticsearch
 */
object FlowdockToElasticsearch extends App {

  logger.info(s"Starting Flowdock To Elasticsearch import ...")

  loadFlows
  loadUsers
  logger.info(s"Found ${flows.size} flows and ${users.size} users")

  loadCurrentStatus
  logger.info(s"Current status is : $currentStatusByFlow")

  createEsIndexesIfNeeded

  flows.values.foreach {
    case (flowName, flowParameterizedName, flowUrl) =>

      val importedMessages = currentStatusByFlow.get(flowParameterizedName) match {

        case Some(latestedMsgId) =>
          logger.info(s"Loading new messages from flow '$flowName' since message ID '$latestedMsgId' ...")
          getMessagesSince(flowName, flowParameterizedName, flowUrl, latestedMsgId)

        case _ =>
          logger.info(s"Loading ALL messages from flow '$flowName' ...")
          getMessagesUntil(flowName, flowParameterizedName, flowUrl, None)
      }

      logger.info(s"$importedMessages messages imported from flow '$flowName'")

      saveCurrentStatus
  }

  logger.info(s"Flowdock To Elasticsearch import finished !")
  esclient.close
  Http.shutdown

  /* ====================================================== */

  def getMessagesSince(flowName: String, flowParameterizedName: String, flowUrl: String, latestMsgId: Int): Int = {
    val json = parse(getUrlContent(s"${flowUrl}/messages?limit=${config.getInt("flowdock.api.max_messages_per_request")}&since_id=${latestMsgId}"))
    val importedMessages = parseAndImportMessages(flowName, json)

    val (resultsCount, firstMsgId, lastMsgId) = json match {
      case JArray(messages) if !messages.isEmpty =>
        val msgIds = messages \ "id" \\ classOf[JInt]
        (messages.size, msgIds.head.toInt, msgIds.last.toInt)
      case _ => (0, 0, 0)
    }

    resultsCount match {
      case resultsCount if (resultsCount > 0) =>
        getMessagesSince(flowName, flowParameterizedName, flowUrl, lastMsgId) + importedMessages
      case resultsCount if (resultsCount == 0) =>
        currentStatusByFlow.put(flowParameterizedName, latestMsgId)
        importedMessages
    }
  }

  def getMessagesUntil(flowName: String, flowParameterizedName: String, flowUrl: String, msgId: Option[Int]): Int = {
    val until = msgId.map(id => "&until_id=" + id).getOrElse("")
    val json = parse(getUrlContent(s"${flowUrl}/messages?limit=${config.getInt("flowdock.api.max_messages_per_request")}" + until))
    val importedMessages = parseAndImportMessages(flowName, json)

    val (resultsCount, firstMsgId, lastMsgId) = json match {
      case JArray(messages) if !messages.isEmpty =>
        val msgIds = messages \ "id" \\ classOf[JInt]
        (messages.size, msgIds.head.toInt, msgIds.last.toInt)
      case _ => (0, 0, 0)
    }

    if (msgId.isEmpty) {
      currentStatusByFlow.put(flowParameterizedName, lastMsgId)
    }

    resultsCount match {
      case resultsCount if (resultsCount > 0) => getMessagesUntil(flowName, flowParameterizedName, flowUrl, Some(firstMsgId)) + importedMessages
      case resultsCount if (resultsCount == 0) => importedMessages
    }
  }

  def parseAndImportMessages(flowName: String, json: JValue): Int = {
    var importedMessages = 0
    for {
      JArray(messages) <- json
      JObject(message) <- messages
      JField("id", JInt(id)) <- message
      JField("app", JString(app)) <- message
      JField("flow", JString(flowid)) <- message
      JField("event", JString(event)) <- message
      JField("user", JString(userid)) <- message
      JField("content", content) <- message
      JField("tags", JArray(rawTags)) <- message
      JField("sent", JInt(timestamp)) <- message
      JField("edited", editedField) <- message
      allTags = rawTags.map(_.extract[String])
      date = dateFormatter.print(timestamp.toLong)
      user = users.get(userid)
    } yield {

      val editionDelay = editedField match {
        case JInt(editionTimestamp) => editionTimestamp - timestamp
        case _ => BigInt(0)
      }

      event match {

        // The message event is sent when a user sends a chat message.
        case "message" =>
          for {
            JString(text) <- content
          } yield {
            esclient.execute {
              index into config.getString("elasticsearch.index.data") -> "thread" fields (
                "flow" -> flowName,
                "thread_id" -> allTags.find(_ == ":thread").map(_ => id.toString).getOrElse(""),
                "thread_title" -> allTags.find(_ == ":thread").map(_ => text).getOrElse(""),
                "author" -> user.map(_._2).getOrElse(userid),
                "@timestamp" -> date,
                "tags" -> allTags.filterNot(_.startsWith(":")).filterNot(_.startsWith("influx:")),
                "highlighted_users" -> allTags.filter(_.startsWith(":user:")).map(_.split(":", 3).apply(2)).map(userid => users.get(userid).map(_._2).getOrElse(userid)),
                "has_url" -> !allTags.filter(_ == ":url").isEmpty,
                "edition_delay" -> editionDelay,
                "has_been_edited" -> (editionDelay > 0),
                "text" -> text //
                ) id s"$flowid-$id"
            }
            importedMessages += 1
          }

        // The comment event is sent when a user comments on an item in the team inbox.
        case "comment" =>
          for {
            JField("title", JString(title)) <- content
            JField("text", JString(text)) <- content
          } yield {
            esclient.execute {
              index into config.getString("elasticsearch.index.data") -> "comment" fields (
                "flow" -> flowName,
                "thread_id" -> allTags.find(_.startsWith("influx:")).map(_.split(":", 2).apply(1)).getOrElse(""),
                "thread_title" -> title,
                "author" -> user.map(_._2).getOrElse(userid),
                "@timestamp" -> date,
                "tags" -> allTags.filterNot(_.startsWith(":")).filterNot(_.startsWith("influx:")),
                "highlighted_users" -> allTags.filter(_.startsWith(":user:")).map(_.split(":", 3).apply(2)).map(userid => users.get(userid).map(_._2).getOrElse(userid)),
                "has_url" -> !allTags.filter(_ == ":url").isEmpty,
                "edition_delay" -> editionDelay,
                "has_been_edited" -> (editionDelay > 0),
                "text" -> text //
                ) id s"$flowid-$id"
            }
            importedMessages += 1
          }

        // An email sent to the flow
        case "mail" =>
          for {
            JField("subject", JString(subject)) <- content
            JField("content", contentField) <- content
            JField("from", JArray(fromFieldArray)) <- content
            JObject(fromField) <- fromFieldArray
            JField("address", JString(fromAddress)) <- fromField
            JField("name", JString(fromName)) <- fromField
          } yield {

            val text = contentField match {
              case JString(text) => Seq(text)
              case JArray(textArray) => Try { textArray.map(_.extract[String]) } getOrElse (Seq.empty)
              case _ => Seq.empty
            }

            esclient.execute {
              index into config.getString("elasticsearch.index.data") -> "mail" fields (
                "flow" -> flowName,
                "@timestamp" -> date,
                "subject" -> subject,
                "text" -> text,
                "author" -> fromName,
                "from" -> fromAddress,
                "tags" -> allTags.filterNot(_.startsWith(":")) //
                ) id s"$flowid-$id"
            }
            importedMessages += 1
          }

        case _ =>
      }
    }
    importedMessages
  }

  def loadCurrentStatus {
    val gets = flows.values.collect {
      case (name, parameterizedName, url) => get id parameterizedName from config.getString("elasticsearch.index.status") -> "status"
    }
    Try {
      val response = esclient.sync.execute {
        multiget(gets.toSeq: _*)
      }
      response.getResponses.map(_.getResponse).foreach { r =>
        if (r.isExists) {
          val flow = r.getId
          val lastMsgId = r.getSource.get("last_msg_id").toString.toInt
          logger.debug(s"Current status for flow $flow : last message ID is $lastMsgId")
          currentStatusByFlow.put(flow, lastMsgId)
        }
      }
    }
  }

  def saveCurrentStatus {
    currentStatusByFlow foreach {
      case (flow, lastMsgId) =>
        esclient.execute {
          index into config.getString("elasticsearch.index.status") -> "status" fields (
            "last_msg_id" -> lastMsgId) id flow
        }
    }
  }

  def createEsIndexesIfNeeded {
    if (!esclient.sync.exists(config.getString("elasticsearch.index.status")).isExists) {
      esclient.execute {
        create index (config.getString("elasticsearch.index.status")) mappings (
          "status" as (
            ("last_msg_id" typed (LongType)) //
            ) all (false) source (true) //
            ) shards (1) replicas (0)
      }
    }
    if (!esclient.sync.exists(config.getString("elasticsearch.index.data")).isExists) {
      esclient.execute {
        create index (config.getString("elasticsearch.index.data")) mappings (
          "thread" as (
            ("flow" typed (StringType) index ("not_analyzed")),
            ("thread_id" typed (StringType) index ("not_analyzed")),
            ("thread_title" typed (StringType) index ("not_analyzed")),
            ("author" typed (StringType) index ("not_analyzed")),
            ("highlighted_users" typed (StringType) index ("not_analyzed")),
            ("tags" typed (StringType) index ("not_analyzed")),
            ("text" typed (StringType) analyzer (StandardAnalyzer)),
            ("edition_delay" typed (LongType)),
            ("has_been_edited" typed (BooleanType)),
            ("has_url" typed (BooleanType)),
            ("@timestamp" typed (DateType)) //
            ) all (false) source (true) size (true),
            "comment" as (
              ("flow" typed (StringType) index ("not_analyzed")),
              ("thread_id" typed (StringType) index ("not_analyzed")),
              ("thread_title" typed (StringType) index ("not_analyzed")),
              ("author" typed (StringType) index ("not_analyzed")),
              ("highlighted_users" typed (StringType) index ("not_analyzed")),
              ("tags" typed (StringType) index ("not_analyzed")),
              ("text" typed (StringType) analyzer (StandardAnalyzer)),
              ("edition_delay" typed (LongType)),
              ("has_been_edited" typed (BooleanType)),
              ("has_url" typed (BooleanType)),
              ("@timestamp" typed (DateType)) //
              ) all (false) source (true) size (true),
              "mail" as (
                ("flow" typed (StringType) index ("not_analyzed")),
                ("subject" typed (StringType) analyzer (StandardAnalyzer)),
                ("text" typed (StringType) analyzer (StandardAnalyzer)),
                ("author" typed (StringType) index ("not_analyzed")),
                ("from" typed (StringType) index ("not_analyzed")),
                ("tags" typed (StringType) index ("not_analyzed")),
                ("@timestamp" typed (DateType)) //
                ) all (false) source (true) size (true) //
                ) shards (1) replicas (0)
      }
    }
  }

  def loadFlows {
    val onlyFlows = config.getStringList("flowdock.flows.only")
    val json = parse(getUrlContent(config.getString("flowdock.api.endpoint.flows")))
    for {
      JArray(flows) <- json
      JObject(flow) <- flows
      JField("id", JString(id)) <- flow
      JField("name", JString(name)) <- flow
      JField("parameterized_name", JString(parameterizedName)) <- flow
      JField("url", JString(url)) <- flow
    } yield {
      if (onlyFlows.isEmpty || onlyFlows.contains(parameterizedName))
        FlowdockToElasticsearch.flows.put(id, (name, parameterizedName, url))
    }
  }

  def loadUsers {
    val json = parse(getUrlContent(config.getString("flowdock.api.endpoint.users")))
    for {
      JArray(users) <- json
      JObject(user) <- users
      JField("id", JInt(id)) <- user
      JField("email", JString(email)) <- user
      JField("name", JString(name)) <- user
      JField("nick", JString(nick)) <- user
    } yield {
      FlowdockToElasticsearch.users.put(id.toString, (email, nick, name))
    }
  }

  private def getUrlContent(serviceUrl: String): String = {
    logger.debug(s"Loading URL $serviceUrl")
    val req = url(serviceUrl).as_!(config.getString("flowdock.api.token"), "")
    val future = Http(req OK as.String)
    val response = future()
    response
  }

  /** latest imported message ID by flow  */
  lazy val currentStatusByFlow = Map[String, Int]()

  /** users (email, nick, name) by id */
  lazy val users = Map[String, (String, String, String)]()

  /** flows (name, parameterized_name, url) by id */
  lazy val flows = Map[String, (String, String, String)]()

  implicit val formats = DefaultFormats
  lazy val dateFormatter = ISODateTimeFormat.dateTime().withZoneUTC
  lazy val logger = LoggerFactory.getLogger("flowdock2es")
  lazy val config = ConfigFactory.load()

  lazy val esclient = {
    val settings = config.getConfig("elasticsearch.client.settings").entrySet.foldLeft(ImmutableSettings.settingsBuilder) { (settings, entry) =>
      settings.put(entry.getKey, entry.getValue.unwrapped)
    }
    ElasticClient.remote(settings.build, (config.getString("elasticsearch.hostname"), config.getInt("elasticsearch.port")))
  }

}