# Streaming examples using Play 2.5 and Akka Streams

## You can read the tutorial [here](http://loicdescotte.github.io/posts/play25-akka-streams/)




## Akka Streams integration in Play Framework 2.5
February 5, 2016
Update 1 (February 10, 2016) : Using latest Play 2.5 SNAPSHOT to benefit from EventSource helper
Update 2 (February 18, 2016) : Upgrade to Play 2.5 RC1 and refactor code (thank you Ahmed Mushtaq for the pull request in the sample app!)
Update 3 (April 10, 2016) : Adding part 2 : Akka Streams, Play Framework and queues

From version 2.5, Play’s default stream processing library is Akka Streams. Akka Streams is an implementation of the Reactive Streams standard. With Play 2.4 it was possible to use Akka Streams via bindings to the Play Iteratee lib (see this post). Now we don’t need this Iteratee bindings anymore, we can use Akka Streams natively in Play controllers. Play 2.5 also comes with Akka Streams 2, which is easier to use and in many cases faster than the first version.

A fake tweet API
In this example, we will create a fake Twitter service with Play. This service will stream messages using an Akka Streams Source :

 ``` def timeline(keyword: String) = Action {
    val source = Source.tick(initialDelay = 0 second, interval = 1 second, tick = "tick")
    Ok.chunked(source.map { tick =>
      val (prefix, author) = prefixAndAuthor
      Json.obj("message" -> s"$prefix $keyword", "author" -> author).toString + "\n"
    }.limit(100))
  }

  private def prefixAndAuthor = {
      // random message and author
  }
  ```
The tick method creates a simple source with 1 second of delay between 2 message emissions. We can transform this source to get a message per second, related to a keyword using map. Finally we are limiting the feed to 100 elements.

A tweet consumer
Now we need another Web application that will be plugged on our fake Twitter service to make several queries in parallel and mix the results in a single source. This source will be streamed using Server Sent Events, which is simpler than WebSockets and a good fit to push data from server to browser.
  ```
case class TweetInfo(searchQuery: String, message: String, author: String)

object TweetInfo {
  implicit val tweetInfoFormat = Json.format[TweetInfo]
}

def mixedStream(queryString: String) = Action {
  val keywordSources = Source(queryString.split(",").toList)
  val responses = keywordSources.flatMapMerge(10, queryToSource)
  Ok.chunked(responses via EventSource.flow)
}
The first step is to call the fake Tweet API for each user query. Then we need to transform the WebService responses into Akka Streams sources. The list of sources we get in result can be merged in a single stream thanks to the flatMapMerge method.

  ```
  Let’s see how to build this sources in detail :

  ```
private def queryToSource(keyword: String) = {
  val request = wSClient
    .url(s"http://localhost:9000/timeline")
    .withQueryString("keyword" -> keyword)

  streamResponse(request)
    .via(Framing.delimiter(ByteString("\n"), maximumFrameLength = 100, allowTruncation = true))
    .map { byteString =>
      val json = Json.parse(byteString.utf8String)
      val tweetInfo = TweetInfo(keyword, (json \ "message").as[String], (json \ "author").as[String])
      Json.toJson(tweetInfo)
    }
}

private def streamResponse(request: WSRequest) = Source.fromFuture(request.stream()).flatMapConcat(_.body)

  ```
Play WS Client stream method returns an Akka Streams Source of Akka ByteString via the body value of the response. The response itself is wrapped in a Future. We can convert a Future object into a Source using Source.fromFuture. As we get a Source of Source of WebService response, we can use flatMapConcat(_.body) to keep only the response body in a single source.

The Twitter service may send several messages in a single chunk, so we need to split them on line breaks. We can also imagine that the Twitter service could send chunks with incomplete messages. In this case the messages need to be saved in a buffer until we reach a line break. Fortunately, the Framing object does all the job of splitting and buffering the chunks for us. We just need to provide a separator (line break) and a max frame length for the source elements. This is a great improvement from Akka Streams 1, which was forcing developers to write a custom line parser. The result of this operation is a new source that can be transformed into the new desired format. In this case we’re just adding the search query in the response to ease filtering on the client side (c.f. TweetInfo case class). Then each source element can be transformed into Json thanks to the TweetInfo implicit Json formatter.

Finally, let’s go back to our mixedStream action. Play’s EventSource.flow method helps us to format the messages into the Server Sent Events format… and the stream can flow! Quite easy isn’t it?
