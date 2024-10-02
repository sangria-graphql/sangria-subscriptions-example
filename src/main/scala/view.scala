import generic.View

class ArticleView extends View[Article, ArticleEvent] {
  val handleEvent: Handler = {
    case event: ArticleCreated =>
      add(Article(event.id, event.version, event.title, event.authorId, event.text))
    case event: ArticleTextChanged =>
      update(event)(_.copy(version = event.version, text = event.text))
    case event: ArticleDeleted =>
      delete(event)
  }
}

class AuthorView extends View[Author, AuthorEvent] {
  val handleEvent: Handler = {
    case event: AuthorCreated =>
      add(Author(event.id, event.version, event.firstName, event.lastName))
    case event: AuthorNameChanged =>
      update(event)(_.copy(version = event.version, firstName = event.firstName, lastName = event.lastName))
    case event: AuthorDeleted =>
      delete(event)
  }
}
