package com.itv.scalapact.shared

import com.itv.scalapact.shared.MessageContentType._

sealed trait JsonRepresentation
object JsonRepresentation {
  object AsString extends JsonRepresentation
  object AsObject extends JsonRepresentation
}

trait MessageContentType {
  def renderString: String

  /**
    * Explicitly states how the message type should be represented in the pact contract
    *
    * XML messages are best represented as strings, while JSON are represented as objects
    *
    * @return [MessageEncoding]
    */
  def jsonRepresentation: JsonRepresentation
}

object MessageContentType {
  case object ApplicationText extends MessageContentType {
    override def renderString: String = "application/text"

    override def jsonRepresentation: JsonRepresentation = JsonRepresentation.AsString
  }
  case object ApplicationJson extends MessageContentType {
    override val renderString       = "application/json"
    override val jsonRepresentation = JsonRepresentation.AsObject
  }
  def apply(mct: String): MessageContentType = mct match {
    case "application/json" => ApplicationJson
  }
  def unnaply(mct: MessageContentType): String = mct.renderString
}

case class Message(description: String,
                   providerStates: List[String],
                   contents: String,
                   metaData: Message.Metadata,
                   matchingRules: Message.MatchingRules,
                   contentType: MessageContentType) {

  def renderAsString: String = s"""Message
                                   |  description:   [$description]
                                   |  contentType: [${contentType.renderString}]
                                   |  providerStates: [${providerStates.mkString(",")}]
                                   |  meta: [${metaData.mkString(",")}]
                                   |  matchingRules: [${matchingRules.mkString(",")}]
                                   |  $contents""".stripMargin
}

object Message {

  type Metadata = Map[String, String]

  type MatchingRules = Map[String, Map[String, Message.Matchers]]

  object MatchingRules {

    def merge(lowPriorityRules: Map[String, MatchingRule], highPriorityRules: MatchingRules): MatchingRules = {
      println(s"$lowPriorityRules")
      lowPriorityRules.foldLeft(highPriorityRules) { (acc, next) =>
        val x = MatchingRules.appendToBody(acc)(next._1, next._2)
        println(s"$x")
        x
      }
    }

    def appendToBody(
        matchingRules: MatchingRules
    )(jsonPath: String, matchingRule: MatchingRule): MatchingRules = {
      val key = jsonPath.replace("$.body", "$")
      matchingRules
        .get("body")
        .fold(matchingRules + ("body" -> Map(key -> Matchers.from(matchingRule))))(
          rules =>
            Map(
              "body" -> rules
                .get(key)
                .fold(rules + (key  -> Matchers.from(matchingRule)))(
                  x => rules + (key -> x.copy(matchers = x.matchers ++ List(matchingRule)))
                )
          )
        )
    }
  }

  case class Matchers(matchers: List[MatchingRule])

  object Matchers {

    def from(rules: MatchingRule*): Message.Matchers = Message.Matchers(rules.toList)
  }

  def apply(description: String, providerStates: List[String], contents: String, metaData: Metadata): Message =
    new Message(description, providerStates, contents, metaData, Map.empty, ApplicationJson)

  object Metadata {
    val empty: Metadata                       = Metadata()
    def apply(x: (String, String)*): Metadata = x.toMap
  }

}

case class Pact(provider: PactActor, consumer: PactActor, interactions: List[Interaction], messages: List[Message]) {
  def withoutSslHeader: Pact = copy(interactions = interactions.map(_.withoutSslHeader))

  def renderAsString: String =
    s"""Pact
       |  consumer: [${consumer.renderAsString}]
       |  provider: [${provider.renderAsString}]
       |  interactions:
       |${interactions.map(_.renderAsString).mkString("\n")}
       |  messages:
       |${messages.map(_.renderAsString).mkString("\n")}
     """.stripMargin

}

case class PactActor(name: String) {
  def renderAsString: String =
    s"""$name"""

}

case class Interaction(provider_state: Option[String],
                       providerState: Option[String],
                       description: String,
                       request: InteractionRequest,
                       response: InteractionResponse) {

  def withoutSslHeader: Interaction =
    copy(request = request.copy(headers = request.headers.map(_ - SslContextMap.sslContextHeaderName)))

  def renderAsString: String =
    s"""Interaction
       |  providerState: [${providerState.orElse(provider_state).getOrElse("<none>")}]
       |  description:   [$description]
       |  ${request.renderAsString}
       |  ${response.renderAsString}
    """.stripMargin

}

case class InteractionRequest(method: Option[String],
                              path: Option[String],
                              query: Option[String],
                              headers: Option[Map[String, String]],
                              body: Option[String],
                              matchingRules: Option[Map[String, MatchingRule]]) {
  def unapply: Option[(Option[String], Option[String], Option[String], Option[Map[String, String]], Option[String])] =
    Some {
      (method, path, query, headers, body)
    }

  def withoutSslContextHeader: InteractionRequest = copy(headers = headers.map(_ - SslContextMap.sslContextHeaderName))

  def sslContextName: Option[String] = headers.flatMap(_.get(SslContextMap.sslContextHeaderName))

  def renderAsString: String =
    s"""Request           [${method.getOrElse("<missing method>")}]
       |  path:           [${path.getOrElse("<missing path>")}]
       |  query:          [${query.getOrElse("<missing path>")}]
       |  headers:        [${headers
         .map(_.toList.map(p => p._1 + "=" + p._2).mkString(",\n                   "))
         .getOrElse("")}]
       |  matching rules: [${matchingRules
         .map(_.toList.map(p => p._1 + " -> (" + p._2.renderAsString + ")").mkString(",\n                   "))
         .getOrElse("")}]
       |  body:
       |${body.getOrElse("[no body]")}
       |
     """.stripMargin

}

case class InteractionResponse(status: Option[Int],
                               headers: Option[Map[String, String]],
                               body: Option[String],
                               matchingRules: Option[Map[String, MatchingRule]]) {

  def renderAsString: String =
    s"""Response          [${status.map(_.toString).getOrElse("<missing status>")}]
       |  headers:        [${headers
         .map(_.toList.map(p => p._1 + "=" + p._2).mkString(",\\n                   \""))
         .getOrElse("")}]
       |  matching rules: [${matchingRules
         .map(_.toList.map(p => p._1 + " -> (" + p._2.renderAsString + ")").mkString(",\n                   "))
         .getOrElse("")}]
       |  body:
       |${body.getOrElse("[no body]")}
       |
     """.stripMargin

}

case class MatchingRule(`match`: Option[String], regex: Option[String], min: Option[Int]) {
  def renderAsString: String =
    s"Rule type: '${`match`.getOrElse("<missing>")}'  regex: '${regex.getOrElse("n/a")}'  min: '${min.map(_.toString).getOrElse("n/a")}'"
}
