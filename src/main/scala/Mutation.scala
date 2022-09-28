import java.util.UUID

import sangria.macros.derive.GraphQLField

import scala.concurrent.Future

trait Mutation {
  this: Ctx ⇒

  @GraphQLField
  def createAuthor(firstName: String, lastName: String): Future[Option[Author]] =
    addEvent[Author](authors, AuthorCreated(UUID.randomUUID.toString, 1, firstName, lastName))

  @GraphQLField
  def changeAuthorName(id: String, version: Long, firstName: String, lastName: String): Future[Option[Author]] =
    for {
      nextVersion ← loadLatestVersion(id, version)
      author ← addEvent[Author](authors, AuthorNameChanged(id, nextVersion, firstName, lastName))
    } yield author

  @GraphQLField
  def deleteAuthor(id: String, version: Long): Future[Author] =
    for {
      nextVersion ← loadLatestVersion(id, version)
      author ← loadAuthor(id)
      _ ← addDeleteEvent(AuthorDeleted(id, nextVersion))
    } yield author

  @GraphQLField
  def createArticle(title: String, authorId: String, text: Option[String]): Future[Option[Article]] =
    for {
      author ← loadAuthor(authorId)
      article ← addEvent[Article](articles, ArticleCreated(UUID.randomUUID.toString, 1, title, author.id, text))
    } yield article

  @GraphQLField
  def changeArticleText(id: String, version: Long, text: Option[String]): Future[Option[Article]] =
    for {
      nextVersion ← loadLatestVersion(id, version)
      article ← addEvent[Article](articles, ArticleTextChanged(id, nextVersion, text))
    } yield article

  @GraphQLField
  def deleteArticle(id: String, version: Long) =
    for {
      version ← loadLatestVersion(id, version)
      article ← loadArticle(id)
      _ ← addDeleteEvent(ArticleDeleted(id, version))
    } yield article
}
