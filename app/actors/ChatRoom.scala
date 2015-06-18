package actors

import actors.ChatRoom.{ChatMessage, Subscribe}
import akka.actor._
import akka.cluster.ClusterEvent.{InitialStateAsEvents, MemberEvent, MemberRemoved, MemberUp, UnreachableMember}
import akka.cluster.{Cluster, Member}
import akka.event.LoggingReceive
import play.api.libs.json.{JsValue, Json, Writes}
import play.twirl.api.HtmlFormat

object ChatRoom {
  case class ChatMessage(user: String, text: String)
  object Subscribe

  object ChatMessage {
    implicit val chatMessageWrites = new Writes[ChatMessage] {
      def writes(chatMessage: ChatMessage): JsValue = {
        Json.obj(
          "type" -> "message",
          "user" -> chatMessage.user,
          "text" -> multiLine(chatMessage.text)
        )
      }
    }

    private def multiLine(text: String) = {
      HtmlFormat.raw(text).body.replace("\n", "<br/>")
    }
  }
}

class ChatRoom extends Actor with ActorLogging {
  val cluster = Cluster(context.system)

  var users = Set[ActorRef]()

  override def preStart(): Unit = {
    cluster.subscribe(self, initialStateMode = InitialStateAsEvents, classOf[MemberEvent], classOf[UnreachableMember])
  }

  def receive = LoggingReceive {
    case Subscribe =>
      users += sender
      context watch sender

    case Terminated(user) =>
      users -= user

    case m: ChatMessage =>
      users foreach { _ forward m }
      forwardToOtherClusterMembers(m)

    case MemberUp(member) =>
      log.info(s"Member is Up: ${member.address}")

    case UnreachableMember(member) =>
      log.info(s"Member detected as unreachable: $member")

    case MemberRemoved(member, previousStatus) =>
      log.info(s"Member is Removed: ${member.address} after $previousStatus")

    case _: MemberEvent => // ignore
  }

  private[actors] def forwardToOtherClusterMembers(chatMessage: ChatMessage) = {
    def forwardToOtherClusterMembers(member: Member) =
      context.actorSelection(RootActorPath(member.address) / "user" / "chat-room") forward chatMessage

    def isLocal(actor: ActorRef) = actor.path.address.toString == context.system.toString

    def otherClusterMembers = cluster.state.members.filterNot(_.address == cluster.selfAddress)

    if (isLocal(sender())) {
      otherClusterMembers foreach forwardToOtherClusterMembers
    }
  }
}
