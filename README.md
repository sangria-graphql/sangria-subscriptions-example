An example of GraphQL akka-http server with SSE-based subscriptions powered by [sangria](http://sangria-graphql.org/). It features:

* Implementation based on CQRS (Command Query Responsibility Segregation) + event-sourcing 
* Server Sent Events subscriptions based on akka-streams and akka-sse
* Implemented with [stream-based subscriptions](http://sangria-graphql.org/learn/#stream-based-subscriptions)  
* Optimistic concurrency control for mutation queries

This example is pretty rough around the edges at the moment. Subscriptions support in GraphQL and well as sangria is still in experimental phase, so expect big changes and improvements in near future (especially around the way subscriptions are implemented). This also means that your feedback is important and very welcome ;)

You can find a WebSocket example in a [separate branch](https://github.com/sangria-graphql/sangria-subscriptions-example/tree/trbngr-websockets).

## How to start

The only prerequisites are [SBT](http://www.scala-sbt.org/download.html) and [Java 8](http://www.oracle.com/technetwork/java/javase/downloads/jdk8-downloads-2133151.html). After you cloned the project, you need to run an application with SBT:
 
```bash
sbt ~reStart
```

This uses an [sbt-revolver](https://github.com/spray/sbt-revolver) plugin. It will automatically compile and restart the server on every change.
   
After you started the server, you can point your browser to following URLs:
 
* [http://localhost:8084](http://localhost:8084) - GraphiQL UI
* [http://localhost:8084/client](http://localhost:8084/client) - Simple Server Sent Events GraphQL client that will stream events based on subscription query. 

## High-level overview

High-level picture looks like this:
 
![Event-stream based subscriptions](http://olegilyenko.github.io/reactive-ecommerce-api-design/assets/img/graphq-subscription-4.svg)

I also described this approach in much more detail here:

[Event-stream based GraphQL subscriptions](https://gist.github.com/OlegIlyenko/a5a9ab1b000ba0b5b1ad)

Please not that this particular example is intended to demonstrate different concepts (in particular GraphQL subscriptions), so it does not have any persistence. This means that `MemoryEventStore` and views keep all of the data in memory.   

## Client-server interaction

If client makes a `subscription` query, then server will respond with `text/event-stream`. For any other query type server will respond with normal JSON reponse. 

Here is an example of interation between client and server where one client subscribes to an event and another client makes a mutation that produces this type of events:

![Client-server interaction](http://olegilyenko.github.io/reactive-ecommerce-api-design/assets/img/client-server.svg)

Since `EventSource` always makes a `GET` request to a SSE endpoint, I added support for `GET` method on `/graphql` endpoint. It takes a GraphQL query as a query parameter.

## Optimistic concurrency control

I find optimistic concurrency control pretty important for this kind of architecture. So I decided to include it in this example application, even though it adds a bit of complexity.

This pattern helps clients to detect conflicts when they are doing mutations. Imagine that two clients would like to change an article at the same time with GraphQL query like this:
  
```js
mutation NewText {
  changeArticleText(id: "123", version: 5, text: "bar")
}
```

Each client will first read an article and then make some decision based on the returned result. Let's say that they both have decided to update the article in different ways. In order to perform the mutation they both need to tell server which version of article they based their decision on. Thanks to version server is able to detect a conflict and only successfully perform one mutation, rejecting the other one:   

![Optimistic concurrency control](http://olegilyenko.github.io/reactive-ecommerce-api-design/assets/img/optimistic-sangria.svg)

## GraphQL subscription semantics

In this particular application a top-level fields on the `Subscription` type represent all available event types. Client only gets events that it subscribed to. For example, given following subscription:

```js
subscription NewAuthors {
  authorCreated {
    id
    version
    firstName
    lastName
  }
}
```

and mutations:

```js
mutation {
  createAuthor(firstName: "John", lastName: "Doe") {
    id, version
  }
}

mutation {
  changeAuthorName(
    id: "b4dd3963-3fdd-4d7a-8105-c33dfc7ddffc", 
    version: 1, 
    firstName: "Jane", 
    lastName: "Doe") {id, version}
}

mutation {
  deleteAuthor(id: "b4dd3963-3fdd-4d7a-8105-c33dfc7ddffc", version: 2) {
    firstName
    lastName
  }
}
```

client will only get following event:

```json
{
  "data": {
    "authorCreated": {
      "id": "b4dd3963-3fdd-4d7a-8105-c33dfc7ddffc",
      "version": 1,
      "firstName": "John",
      "lastName": "Doe"
    }
  }
}
```

### Multi-field subscriptions

Clint can also subscribe to multiple events. For example:

```js
subscription {
  authorCreated {
    id
    version
    firstName
    lastName
  }
  
  authorDeleted {
    id
    version
  }
}
```

Given the 3 mutation queries mentioned above, client will get following events:

```json
{
  "data": {
    "authorCreated": {
      "id": "b4dd3963-3fdd-4d7a-8105-c33dfc7ddffc",
      "version": 1,
      "firstName": "John",
      "lastName": "Doe"
    }
  }
}

{
  "data": {
    "authorDeleted": {
      "id": "b4dd3963-3fdd-4d7a-8105-c33dfc7ddffc",
      "version": 3
    }
  }
}
```

As you can see, at any given time client can only get 1 event. All other subscription fields would be `null`.

I also would like to mention, that this semantics is pretty arbitrary. GraphQL specification does not define semantics for subscription queries at the moment - it's still under active discussion. So you can treat semantics, that is defined in this example application, more as an experiment rather that a recommended way of doing things ;) 

## Feedback

Feedback is very welcome in any form :) Feel free to make PRs, post issues or join [the chat](https://gitter.im/sangria-graphql/sangria).

 

