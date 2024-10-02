import akka.actor.{Props, ActorSystem, ActorRef, Actor, Stash}
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.stream.ActorMaterializer
import akka.stream.stage.GraphStage
import akka.stream.scaladsl.{BroadcastHub, Keep, Source}
import akka.util.Timeout
import generic.{Event, MemoryEventStore, View}
import sangria.ast.OperationType
import sangria.execution.deferred.DeferredResolver
import sangria.execution.{ErrorWithResolver, Executor, QueryAnalysisError}
import sangria.marshalling.sprayJson._
import sangria.parser.{QueryParser, SyntaxError}
import spray.json._

import akka.pattern.{ask, pipe}
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.control.NonFatal
import scala.util.{Failure, Success}
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._

object Server extends App {
  implicit val system: ActorSystem = ActorSystem("server")
  val logger = Logging(system, getClass)

  import system.dispatcher

  implicit val timeout: Timeout = Timeout(10 seconds)

  val articlesView = system.actorOf(Props[ArticleView]())
  val authorsView = system.actorOf(Props[AuthorView]())
  val eventStoreFeeder: ActorRef = system.actorOf(Props(new Actor with Stash {
    def receive: Receive = {
      case MemoryEventStore.AssignStageActor(stageActor: ActorRef) =>
        unstashAll()
        context.become(receiveNew(stageActor))
      case _ => stash()
    }

    def receiveNew(memoryStoreActor: ActorRef): Receive = { event =>
      (memoryStoreActor ? event).pipeTo(sender())
    }

  }))
  val eventStore = new MemoryEventStore(sourceFeeder = eventStoreFeeder);

  // this a stream with an actor as a source
  // and a broadcast hub to send them to views and dynamic subscriptions
  val events = Source
    .fromGraph(eventStore)
    .toMat(BroadcastHub.sink)(Keep.right)
    .run()

  // Connect event store to views
  View.asSink(articlesView).runWith(events)
  View.asSink(authorsView).runWith(events)

  val executor = Executor(schema.createSchema, deferredResolver = DeferredResolver.fetchers(schema.authors))

  def executeQuery(query: String, operation: Option[String], variables: JsObject = JsObject.empty): Route = {
    val ctx = Ctx(authorsView, articlesView, eventStoreFeeder, events, system.dispatcher, timeout)

    QueryParser.parse(query) match {
      // Query is parsed successfully, let's execute it
      case Success(queryAst) =>
        queryAst.operationType(operation) match {

          // subscription queries will produce `text/event-stream` response
          case Some(OperationType.Subscription) =>
            import sangria.execution.ExecutionScheme.Stream
            import sangria.streaming.akkaStreams._

            complete(
              executor
                .prepare(queryAst, ctx, (), operation, variables)
                .map { preparedQuery =>
                  ToResponseMarshallable(
                    preparedQuery
                      .execute()
                      .map(result => ServerSentEvent(result.compactPrint))
                      .recover { case NonFatal(error) =>
                        logger.error(error, "Unexpected error during event stream processing.")
                        ServerSentEvent(error.getMessage)
                      }
                  )
                }
                .recover {
                  case error: QueryAnalysisError => ToResponseMarshallable(BadRequest -> error.resolveError)
                  case error: ErrorWithResolver  => ToResponseMarshallable(InternalServerError -> error.resolveError)
                }
            )

          // all other queries will just return normal JSON response
          case _ =>
            complete(
              executor
                .execute(queryAst, ctx, (), operation, variables)
                .map(OK -> _)
                .recover {
                  case error: QueryAnalysisError => BadRequest -> error.resolveError
                  case error: ErrorWithResolver  => InternalServerError -> error.resolveError
                }
            )
        }

      // Query contains syntax errors
      case Failure(error: SyntaxError) =>
        complete(
          BadRequest,
          JsObject(
            "syntaxError" -> JsString(error.getMessage),
            "locations" -> JsArray(
              JsObject(
                "line" -> JsNumber(error.originalError.position.line),
                "column" -> JsNumber(error.originalError.position.column)
              )
            )
          )
        )

      case Failure(error) =>
        complete(InternalServerError)
    }
  }

  val route: Route =
    (post & path("graphql")) {
      entity(as[JsValue]) { requestJson =>
        val JsObject(fields) = requestJson

        val JsString(query) = fields("query")

        val operation = fields.get("operationName") collect { case JsString(op) =>
          op
        }

        val vars = fields.get("variables") match {
          case Some(obj: JsObject) => obj
          case _                   => JsObject.empty
        }

        executeQuery(query, operation, vars)
      }
    } ~
      (get & path("graphql")) {
        parameters(Symbol("query"), Symbol("operation").?) { (query, operation) =>
          executeQuery(query, operation)
        }
      } ~
      (get & path("client")) {
        getFromResource("web/client.html")
      } ~
      get {
        getFromResource("web/graphiql.html")
      }

  Http().newServerAt("0.0.0.0", 8084).bindFlow(route)
}
