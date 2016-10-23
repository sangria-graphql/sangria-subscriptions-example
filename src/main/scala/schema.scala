import akka.actor.ActorRef
import akka.util.Timeout
import generic.{Event, View, Versioned}
import generic.View.Get
import sangria.execution.UserFacingError
import sangria.schema._
import sangria.macros.derive._
import akka.pattern.ask

import scala.annotation.unchecked.uncheckedVariance
import scala.collection.immutable.ListMap
import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag

object schema {
  case class MutationError(message: String) extends Exception(message) with UserFacingError
  case class SubscriptionField[+T : ClassTag](tpe: ObjectType[Ctx, T @uncheckedVariance]) {
    lazy val clazz = implicitly[ClassTag[T]].runtimeClass

    def value(v: Any): Option[T] = v match {
      case v if clazz.isAssignableFrom(v.getClass) ⇒ Some(v.asInstanceOf[T])
      case _ ⇒ None
    }
  }

  val EventType = InterfaceType("Event", fields[Ctx, Event](
    Field("id", StringType, resolve = _.value.id),
    Field("version", LongType, resolve = _.value.version)))

  val AuthorCreatedType = deriveObjectType[Unit, AuthorCreated](Interfaces(EventType))
  val AuthorNameChangedType = deriveObjectType[Unit, AuthorNameChanged](Interfaces(EventType))
  val AuthorDeletedType = deriveObjectType[Unit, AuthorDeleted](Interfaces(EventType))

  val ArticleCreatedType = deriveObjectType[Unit, ArticleCreated](Interfaces(EventType))
  val ArticleTextChangedType = deriveObjectType[Unit, ArticleTextChanged](Interfaces(EventType))
  val ArticleDeletedType = deriveObjectType[Unit, ArticleDeleted](Interfaces(EventType))

  val SubscriptionFields = ListMap[String, SubscriptionField[Event]](
    "authorCreated" → SubscriptionField(AuthorCreatedType),
    "authorNameChanged" → SubscriptionField(AuthorNameChangedType),
    "authorDeleted" → SubscriptionField(AuthorDeletedType),
    "articleCreated" → SubscriptionField(ArticleCreatedType),
    "articleTextChanged" → SubscriptionField(ArticleTextChangedType),
    "articleDeleted" → SubscriptionField(ArticleDeletedType))

  def subscriptionFieldName(event: Event) =
    SubscriptionFields.find(_._2.clazz.isAssignableFrom(event.getClass)).map(_._1)

  def createSchema(implicit timeout: Timeout, ec: ExecutionContext) = {
    val VersionedType = InterfaceType("Versioned", fields[Ctx, Versioned](
      Field("id", StringType, resolve = _.value.id),
      Field("version", LongType, resolve = _.value.version)))

    implicit val AuthorType = deriveObjectType[Unit, Author](Interfaces(VersionedType))

    implicit val ArticleType = deriveObjectType[Ctx, Article](
      Interfaces(VersionedType),
      ReplaceField("authorId", Field("author", OptionType(AuthorType), resolve = c ⇒
        (c.ctx.authors ? Get(c.value.authorId)).mapTo[Option[Author]])))

    val IdArg = Argument("id", StringType)
    val OffsetArg = Argument("offset", OptionInputType(IntType), 0)
    val LimitArg = Argument("limit", OptionInputType(IntType), 100)

    def entityFields[T](name: String, tpe: ObjectType[Ctx, T], actor: Ctx ⇒ ActorRef) = fields[Ctx, Any](
      Field(name, OptionType(tpe),
        arguments = IdArg :: Nil,
        resolve = c ⇒ (actor(c.ctx) ? Get(c.arg(IdArg))).mapTo[Option[T]]),
      Field(name + "s", ListType(tpe),
        arguments = OffsetArg :: LimitArg :: Nil,
        resolve = c ⇒ (actor(c.ctx) ? View.List(c.arg(OffsetArg), c.arg(LimitArg))).mapTo[Seq[T]]))

    val QueryType = ObjectType("Query",
      entityFields[Author]("author", AuthorType, _.authors) ++
      entityFields[Article]("article", ArticleType, _.articles))

    val MutationType = deriveContextObjectType[Ctx, Mutation, Any](identity)

    val SubscriptionType = ObjectType("Subscription",
      SubscriptionFields.toList.map { case (name, field) ⇒
        Field(name, OptionType(field.tpe), resolve = (c: Context[Ctx, Any]) ⇒ field.value(c.value))
      })

    Schema(QueryType, Some(MutationType), Some(SubscriptionType))
  }
}
