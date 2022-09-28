package generic

import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.{Cancel, Request}

class MemoryEventStore extends ActorPublisher[Event] {
  import MemoryEventStore._

  // internal state, only the latest version is useful for commands
  var eventVersions = Map.empty[String, Long]

  var eventBuffer = Vector.empty[Event]

  def receive = {
    case AddEvent(event) if eventBuffer.size >= MaxBufferCapacity ⇒
      sender() ! OverCapacity(event)

    case LatestEventVersion(id) ⇒ sender() ! eventVersions.get(id)

    case AddEvent(event) ⇒ eventVersions.get(event.id) match {
      case None ⇒
        addEvent(event)
        sender() ! EventAdded(event)
      case Some(version) if version == event.version - 1 ⇒
        addEvent(event)
        sender() ! EventAdded(event)
      case Some(version) ⇒ sender() ! ConcurrentModification(event, version)
    }

    case Request(_) ⇒ deliverEvents()
    case Cancel ⇒ context.stop(self)
  }

  def addEvent(event: Event) = {
    eventVersions + (event.id → event.version)
    eventBuffer = eventBuffer :+ event

    deliverEvents()
  }

  def deliverEvents(): Unit = {
    if (isActive && totalDemand > 0) {
      val (use, keep) = eventBuffer.splitAt(totalDemand.toInt)

      eventBuffer = keep

      use foreach onNext
    }
  }
}

object MemoryEventStore {
  case class AddEvent(event: Event)
  case class LatestEventVersion(id: String)

  case class EventAdded(event: Event)
  case class OverCapacity(event: Event)
  case class ConcurrentModification(event: Event, latestVersion: Long)

  val MaxBufferCapacity = 1000
}