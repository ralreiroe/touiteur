package controllers

import javax.inject.Inject

import akka.NotUsed
import akka.actor.Cancellable
import akka.stream.scaladsl.{Source, _}
import akka.util._
import play.api.libs.EventSource
import play.api.libs.json._
import play.api.libs.ws.{StreamedResponse, WSClient, WSRequest}
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._


/**
  * http://loicdescotte.github.io/posts/play25-akka-streams/
  *
  * https://github.com/loicdescotte/touiteur
  */

case class TweetInfo(searchQuery: String, msg: String, autor: String)

object TweetInfo {
  implicit val tweetInfoFormat = Json.format[TweetInfo]
}

class Application @Inject()(wSClient: WSClient)(implicit ec: ExecutionContext) extends Controller {


  def index = Action {
    //default search
    Redirect(routes.Application.liveTouits(List("java", "ruby")))
  }

  def liveTouits(queries: List[String]) = Action {
    Ok(views.html.index(queries))
  }

  private def randomPrefixAndAuthor = {
    import java.util.Random
    val prefixes = List("Well done", "You're an ace", "I love")
    val authors = List("Bob", "Joe", "John")
    val rand = new Random()
    (prefixes(rand.nextInt(prefixes.length)), authors(rand.nextInt(authors.length)))
  }

  /**
    * endpoint that returns 3 tweets as "\n"-delimited String in json format.
    * """{"message": "Tweet about java", "author": "John"} \n
    *
    * http://localhost:9000/timeline?keyword=66666
{"message":"You're an ace 66666","author":"Bob"}

{"message":"I love 66666","author":"Joe"}

{"message":"You're an ace 66666","author":"Joe"}

    */
  def timeline(keyword: String) = Action {
    val source: Source[String, Cancellable] = Source.tick(initialDelay = 0.second, interval = 1.second, tick = "tick")
    val value: Source[String, Cancellable] = source.map { tick =>
      val (prefix, author) = randomPrefixAndAuthor
      val str: String = Json.obj("message" -> s"$prefix $keyword", "author" -> author).toString + "\n"
      println(str)
      str
    }
    println("sleeping in timeline. just to demonstrate that no ticks are produced yet")
    Thread.sleep(3000)
    Ok.chunked(value.limit(2))    //this activates the source, emitting periodic random json strings. But only the first two are send to the client

    //I see about 4-5 ticks produced. not sure what cancels it
  }

  val framing = Framing.delimiter(ByteString("\n"), maximumFrameLength = 100, allowTruncation = true)


  /**
    * merges several Source[JsValue, _] (one per query parameter value) into single Source
    * Sends JsValues as Server Sent Events to the client
    *
    * example:
    * http://localhost:9000/mixedStream?queries=a,b
data: {"searchQuery":"a","msg":"You're an ace a","autor":"Joe"}

data: {"searchQuery":"a","msg":"Well done a","autor":"Bob"}

data: {"searchQuery":"a","msg":"Well done a","autor":"John"}

data: {"searchQuery":"a","msg":"I love a","autor":"Joe"}
    */
  def mixedStream(queryString: String) = Action {
    //convert List of query parameters into akka Source of query parameters
    println(s"###########mixedStream called with ${queryString}")
    val keywordsAsSource: Source[String, NotUsed] = akka.stream.scaladsl.Source(queryString.split(",").toList)
    val responses: Source[JsValue, NotUsed] = keywordsAsSource.flatMapMerge(10, tweetsWithKeyword)
    Ok.chunked(responses via EventSource.flow)    //send JsValue Stream as Server Sent Events
  }

  /**
    * turns a Source of Strings """{"message":"You're an ace 66666","author":"Bob"}""" obtained from call to timeline into
    * a Source of JsValues {"searchQuery": "66666", "msg": "You're an ace 66666", "autor": "Bob"}
    */
  private def tweetsWithKeyword(keyword: String): (Source[JsValue, NotUsed]) = {
    println("calling timeline with: " + keyword)
    val request: WSRequest = wSClient
      .url(s"http://localhost:9000/timeline")
      .withQueryString("keyword" -> keyword)

    //Execute WSRequest and stream the response body
    val eventualStreamedResponse: Future[StreamedResponse] = request.stream()
    val source: Source[StreamedResponse, NotUsed] = Source.fromFuture(eventualStreamedResponse)
    //Turn concatenated bodies into continuous stream of ByteStrings
    val twitterBodySource: Source[ByteString, NotUsed] = source.flatMapConcat((r: StreamedResponse) => r.body)
    twitterBodySource
      .via(framing)   //split stream of ByteString on line breaks
      .map { byteString =>      //parse and transform
      val json = Json.parse(byteString.utf8String)
      val tweetInfo = TweetInfo(keyword, (json \ "message").as[String], (json \ "author").as[String])
      Json.toJson(tweetInfo)
    }
  }

  //unused
  private def streamResponse(request: WSRequest): Source[ByteString, NotUsed] = {
    val eventualStreamedResponse = request.stream()
    val byteStringSource: Source[ByteString, NotUsed] = Source.fromFuture(eventualStreamedResponse).flatMapConcat((r: StreamedResponse) => r.body)
    byteStringSource
  }

}
