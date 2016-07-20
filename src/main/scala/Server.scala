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
import generic.{Event, MemoryEventStore}
import sangria.ast.OperationType
import sangria.execution.{ErrorWithResolver, Executor, QueryAnalysisError}
import sangria.marshalling.sprayJson._
import sangria.parser.{QueryParser, SyntaxError}
import spray.json._

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}

object Server extends App with SubscriptionSupport {
  implicit val system = ActorSystem("server")
  implicit val materializer = ActorMaterializer()
  val logger = Logging(system, getClass)

  import system.dispatcher

  implicit val timeout = Timeout(10 seconds)

  val articlesView = system.actorOf(Props[ArticleView])
  val articlesSink = Sink.fromSubscriber(ActorSubscriber[ArticleEvent](articlesView))

  val authorsView = system.actorOf(Props[AuthorView])
  val authorsSink = Sink.fromSubscriber(ActorSubscriber[AuthorEvent](authorsView))

  val eventStore = system.actorOf(Props[MemoryEventStore])
  val eventStorePublisher =
    Source.fromPublisher(ActorPublisher[Event](eventStore))
      .runWith(Sink.asPublisher(fanout = true))

  val subscriptionEventPublisher = system actorOf Props(new SubscriptionEventPublisher(eventStorePublisher))

  // Connect event store to views
  Source.fromPublisher(eventStorePublisher).collect { case event: ArticleEvent ⇒ event }.to(articlesSink).run()
  Source.fromPublisher(eventStorePublisher).collect { case event: AuthorEvent ⇒ event }.to(authorsSink).run()

  val ctx = Ctx(authorsView, articlesView, eventStore, system.dispatcher, timeout)

  val executor = Executor(schema.createSchema)

  def executeQuery(query: String, operation: Option[String], variables: JsObject = JsObject.empty) =
    QueryParser.parse(query) match {
      case Success(queryAst) ⇒
        queryAst.operationType(operation) match {

          case Some(OperationType.Subscription) ⇒
            complete(ToResponseMarshallable(BadRequest → JsString("Subscriptions not supported via HTTP. Use WebSockets")))

          // all other queries will just return normal JSON response
          case _ ⇒
            complete(executor.execute(queryAst, ctx, (), operation, variables)
              .map(OK → _)
              .recover {
                case error: QueryAnalysisError ⇒ BadRequest → error.resolveError
                case error: ErrorWithResolver ⇒ InternalServerError → error.resolveError
              })
        }

      case Failure(error: SyntaxError) ⇒
        complete(ToResponseMarshallable(BadRequest → JsObject(
          "syntaxError" → JsString(error.getMessage),
          "locations" → JsArray(JsObject(
            "line" → JsNumber(error.originalError.position.line),
            "column" → JsNumber(error.originalError.position.column))))))

      case Failure(error) ⇒
        complete(ToResponseMarshallable(InternalServerError -> JsString(error.getMessage)))
    }

  val route: Route =
    path("graphql") {
      post {
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
        get(handleWebSocketMessages(graphQlSubscriptionSocket(subscriptionEventPublisher, ctx)))
    } ~
      (get & path("client")) {
        getFromResource("web/client.html")
      } ~
      get {
        getFromResource("web/graphiql.html")
      }

  Http().bindAndHandle(route, "0.0.0.0", 8080)
}
