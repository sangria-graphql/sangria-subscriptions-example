package generic

import akka.stream.stage.{StageLogging, GraphStageLogic, GraphStage, OutHandler}
import akka.stream.{Attributes, Outlet, SourceShape}
import akka.actor.ActorRef

class MemoryEventStore(sourceFeeder: ActorRef) extends GraphStage[SourceShape[Event]] {
  import MemoryEventStore._

  // internal state, only the latest version is useful for commands
  var eventVersions = Map.empty[String, Long]

  private val out: Outlet[Event] = Outlet("MessageSource");
  override def shape: SourceShape[Event] = SourceShape(out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with StageLogging {
      lazy val self: GraphStageLogic.StageActor = getStageActor(onMessage)

      var eventBuffer = Vector.empty[Event]

      setHandler(
        out,
        new OutHandler {
          override def onPull() = deliverEvents()
        }
      )

      override def preStart(): Unit = {
        sourceFeeder ! AssignStageActor(self.ref)
      }

      private def onMessage(x: (ActorRef, Any)) = x match {
        case (sender, eventToAdd: AddEvent) if (eventBuffer.size >= MaxBufferCapacity) => {
          sender ! OverCapacity(eventToAdd.event)
        }
        case (sender, latestEvent: LatestEventVersion) => {
          sender ! eventVersions.get(latestEvent.id)
        }
        case (sender, eventToAdd: AddEvent) =>
          eventVersions.get(eventToAdd.event.id) match {
            case None =>
              addEvent(eventToAdd.event)
              sender ! EventAdded(eventToAdd.event)
            case Some(version) if version == eventToAdd.event.version - 1 =>
              addEvent(eventToAdd.event)
              sender ! EventAdded(eventToAdd.event)
            case Some(version) => sender ! ConcurrentModification(eventToAdd.event, version)
          }
      }

      private def addEvent(event: Event) = {
        eventVersions + (event.id -> event.version)
        eventBuffer = eventBuffer :+ event

        deliverEvents()
      }

      private def deliverEvents(): Unit = {
        val (use, keep) = eventBuffer.splitAt(1)
        eventBuffer = keep
        use.foreach(element => push(out, element))
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

  case class AssignStageActor(actorRef: ActorRef)
}
