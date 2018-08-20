import akka.actor.{ActorSystem, Props}
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.stream.ActorMaterializer
import akka.stream.actor.{ActorPublisher, ActorSubscriber}
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import de.heikoseeberger.akkasse.scaladsl.model.ServerSentEvent
import generic.{Event, MemoryEventStore}
import sangria.ast.OperationType
import sangria.execution.deferred.DeferredResolver
import sangria.execution.{ErrorWithResolver, Executor, QueryAnalysisError}
import sangria.marshalling.sprayJson._
import sangria.parser.{QueryParser, SyntaxError}
import spray.json._
import de.heikoseeberger.akkasse.scaladsl.marshalling.EventStreamMarshalling
import de.heikoseeberger.akkasse.scaladsl.model.ServerSentEvent
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

object Server extends App {
  implicit val system = ActorSystem("server")
  implicit val materializer = ActorMaterializer()
  val logger = Logging(system, getClass)

  import system.dispatcher
  import Directives._
  import EventStreamMarshalling._

  implicit val timeout = Timeout(10 seconds)

  val articlesView = system.actorOf(Props[ArticleView])
  val articlesSink = Sink.fromSubscriber(ActorSubscriber[ArticleEvent](articlesView))

  val authorsView = system.actorOf(Props[AuthorView])
  val authorsSink = Sink.fromSubscriber(ActorSubscriber[AuthorEvent](authorsView))

  val eventStore = system.actorOf(Props[MemoryEventStore])
  val eventStorePublisher =
    Source.fromPublisher(ActorPublisher[Event](eventStore))
      .runWith(Sink.asPublisher(fanout = true))

  // Connect event store to views
  Source.fromPublisher(eventStorePublisher).collect { case event: ArticleEvent ⇒ event }.to(articlesSink).run()
  Source.fromPublisher(eventStorePublisher).collect { case event: AuthorEvent ⇒ event }.to(authorsSink).run()

  val executor = Executor(schema.createSchema, deferredResolver = DeferredResolver.fetchers(schema.authors))

  def executeQuery(query: String, operation: Option[String], variables: JsObject = JsObject.empty) = {
    val ctx = Ctx(authorsView, articlesView, eventStore, eventStorePublisher, system.dispatcher, timeout)

    QueryParser.parse(query) match {
      // Query is parsed successfully, let's execute it
      case Success(queryAst) ⇒
        queryAst.operationType(operation) match {

          // subscription queries will produce `text/event-stream` response
          case Some(OperationType.Subscription) ⇒
            import sangria.execution.ExecutionScheme.Stream
            import sangria.streaming.akkaStreams._

            complete(
              executor.prepare(queryAst, ctx, (), operation, variables)
                .map { preparedQuery ⇒
                  ToResponseMarshallable(preparedQuery.execute()
                    .map(result ⇒ ServerSentEvent(result.compactPrint))
                    .recover { case NonFatal(error) ⇒
                      logger.error(error, "Unexpected error during event stream processing.")
                      ServerSentEvent(error.getMessage)
                    })
                }
                .recover {
                  case error: QueryAnalysisError ⇒ ToResponseMarshallable(BadRequest → error.resolveError)
                  case error: ErrorWithResolver ⇒ ToResponseMarshallable(InternalServerError → error.resolveError)
                })

          // all other queries will just return normal JSON response
          case _ ⇒
            complete(executor.execute(queryAst, ctx, (), operation, variables)
              .map(OK → _)
              .recover {
                case error: QueryAnalysisError ⇒ BadRequest → error.resolveError
                case error: ErrorWithResolver ⇒ InternalServerError → error.resolveError
              })
        }

      // Query contains syntax errors
      case Failure(error: SyntaxError) ⇒
        complete(BadRequest, JsObject(
          "syntaxError" → JsString(error.getMessage),
          "locations" → JsArray(JsObject(
            "line" → JsNumber(error.originalError.position.line),
            "column" → JsNumber(error.originalError.position.column)))))

      case Failure(error) ⇒
        complete(InternalServerError)
    }
  }

  val route: Route =
    (post & path("graphql")) {
      entity(as[JsValue]) { requestJson ⇒
        val JsObject(fields) = requestJson

        val JsString(query) = fields("query")

        val operation = fields.get("operationName") collect {
          case JsString(op) ⇒ op
        }

        val vars = fields.get("variables") match {
          case Some(obj: JsObject) ⇒ obj
          case _ ⇒ JsObject.empty
        }

        executeQuery(query, operation, vars)
      }
    } ~
      (get & path("graphql")) {
        parameters('query, 'operation.?) { (query, operation) ⇒
          executeQuery(query, operation)
        }
      } ~
      (get & path("client")) {
        getFromResource("web/client.html")
      } ~
      get {
        getFromResource("web/graphiql.html")
      }

  Http().bindAndHandle(route, "0.0.0.0", 8080)
}
