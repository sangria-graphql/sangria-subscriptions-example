import akka.actor.ActorRef
import akka.util.Timeout
import generic.{Event, Versioned, View}
import generic.View.Get
import sangria.execution.UserFacingError
import sangria.schema._
import sangria.macros.derive._
import akka.pattern.ask
import akka.stream.Materializer
import sangria.execution.deferred.{Fetcher, HasId}

import scala.concurrent.ExecutionContext
import sangria.streaming.akkaStreams._

object schema {
  case class MutationError(message: String) extends Exception(message) with UserFacingError

  val authors = Fetcher.caching((c: Ctx, ids: Seq[String]) => c.loadAuthors(ids))(HasId(_.id))

  def createSchema(implicit timeout: Timeout, ec: ExecutionContext, mat: Materializer) = {
    val VersionedType = InterfaceType(
      "Versioned",
      fields[Ctx, Versioned](
        Field("id", StringType, resolve = _.value.id),
        Field("version", LongType, resolve = _.value.version)
      )
    )

    implicit val AuthorType = deriveObjectType[Unit, Author](Interfaces(VersionedType))

    val EventType = InterfaceType(
      "Event",
      fields[Ctx, Event](
        Field("id", StringType, resolve = _.value.id),
        Field("version", LongType, resolve = _.value.version)
      )
    )

    val AuthorCreatedType = deriveObjectType[Unit, AuthorCreated](Interfaces(EventType))
    val AuthorNameChangedType = deriveObjectType[Unit, AuthorNameChanged](Interfaces(EventType))
    val AuthorDeletedType = deriveObjectType[Unit, AuthorDeleted](Interfaces(EventType))

    val ArticleCreatedType = deriveObjectType[Unit, ArticleCreated](
      Interfaces(EventType),
      ReplaceField(
        "authorId",
        Field("author", OptionType(AuthorType), resolve = c => authors.deferOpt(c.value.authorId))
      )
    )

    val ArticleTextChangedType = deriveObjectType[Unit, ArticleTextChanged](Interfaces(EventType))
    val ArticleDeletedType = deriveObjectType[Unit, ArticleDeleted](Interfaces(EventType))

    implicit val ArticleType = deriveObjectType[Ctx, Article](
      Interfaces(VersionedType),
      ReplaceField(
        "authorId",
        Field("author", OptionType(AuthorType), resolve = c => authors.deferOpt(c.value.authorId))
      )
    )

    val IdArg = Argument("id", StringType)
    val OffsetArg = Argument("offset", OptionInputType(IntType), 0)
    val LimitArg = Argument("limit", OptionInputType(IntType), 100)

    def entityFields[T](name: String, tpe: ObjectType[Ctx, T], actor: Ctx => ActorRef) = fields[Ctx, Unit](
      Field(
        name,
        OptionType(tpe),
        arguments = IdArg :: Nil,
        resolve = c => (actor(c.ctx) ? Get(c.arg(IdArg))).mapTo[Option[T]]
      ),
      Field(
        name + "s",
        ListType(tpe),
        arguments = OffsetArg :: LimitArg :: Nil,
        resolve = c => (actor(c.ctx) ? View.List(c.arg(OffsetArg), c.arg(LimitArg))).mapTo[Seq[T]]
      )
    )

    val QueryType = ObjectType(
      "Query",
      entityFields[Author]("author", AuthorType, _.authors) ++
        entityFields[Article]("article", ArticleType, _.articles)
    )

    val MutationType = deriveContextObjectType[Ctx, Mutation, Unit](identity)

    /** creates a subscription field for a specific event type */
    def subscriptionField[T <: Event](tpe: ObjectType[Ctx, T]) = {
      val fieldName = s"${tpe.name.head.toLower}${tpe.name.tail}"

      Field.subs(
        fieldName,
        tpe,
        resolve = (c: Context[Ctx, Unit]) =>
          c.ctx.eventStream
            .filter(event => tpe.valClass.isAssignableFrom(event.getClass))
            .map(event => Action(event.asInstanceOf[T]))
      )
    }

    val SubscriptionType = ObjectType(
      "Subscription",
      fields[Ctx, Unit](
        subscriptionField(AuthorCreatedType),
        subscriptionField(AuthorNameChangedType),
        subscriptionField(AuthorDeletedType),
        subscriptionField(ArticleCreatedType),
        subscriptionField(ArticleTextChangedType),
        subscriptionField(ArticleDeletedType),
        Field.subs("allEvents", EventType, resolve = _.ctx.eventStream.map(Action(_)))
      )
    )

    Schema(QueryType, Some(MutationType), Some(SubscriptionType))
  }
}
