/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.sync

import android.content.Context
import com.waz.ZLog._
import com.waz.model.UserData.ConnectionStatus
import com.waz.model._
import com.waz.model.otr.ClientId
import com.waz.model.sync.SyncJob.Priority
import com.waz.model.sync._
import com.waz.service._
import com.waz.service.conversation.ConversationsService
import com.waz.sync.otr.OtrClientsSyncHandler
import com.waz.sync.queue.ConvLock
import com.waz.threading.Threading
import com.waz.utils.events.Signal
import org.threeten.bp.Instant

import scala.concurrent.Future
import scala.concurrent.duration._

trait SyncServiceHandle {
  def syncUsersIfNotEmpty(ids: Seq[UserId]): Future[Unit] = if (ids.nonEmpty) syncUsers(ids: _*).map(_ => ())(Threading.Background) else Future.successful(())

  def syncSearchQuery(cache: SearchQueryCache): Future[SyncId]
  def syncUsers(ids: UserId*): Future[SyncId]
  def syncSelfUser(): Future[SyncId]
  def deleteAccount(): Future[SyncId]
  def syncConversations(dependsOn: Option[SyncId] = None): Future[SyncId]
  def syncConversation(id: ConvId, dependsOn: Option[SyncId] = None): Future[SyncId]
  def syncCallState(id: ConvId, fromFreshNotification: Boolean, priority: Int = Priority.Normal): Future[SyncId]
  def syncConnectedUsers(): Future[SyncId]
  def syncConnections(dependsOn: Option[SyncId] = None): Future[SyncId]
  def syncCommonConnections(id: UserId): Future[SyncId]
  def syncRichMedia(id: MessageId, priority: Int = Priority.MinPriority): Future[SyncId]

  def postSelfUser(info: UserInfo): Future[SyncId]
  def postSelfPicture(picture: Option[AssetId]): Future[SyncId]
  def postMessage(id: MessageId, conv: ConvId): Future[SyncId]
  def postDeleted(conv: ConvId, msg: MessageId): Future[SyncId]
  def postAssetStatus(id: MessageId, conv: ConvId, status: AssetStatus.Syncable): Future[SyncId]
  def postLiking(id: ConvId, liking: Liking): Future[SyncId]
  def postConnection(user: UserId, name: String, message: String): Future[SyncId]
  def postConnectionStatus(user: UserId, status: ConnectionStatus): Future[SyncId]
  def postConversationName(id: ConvId, name: String): Future[SyncId]
  def postConversationMemberJoin(id: ConvId, members: Seq[UserId]): Future[SyncId]
  def postConversationMemberLeave(id: ConvId, member: UserId): Future[SyncId]
  def postConversationState(id: ConvId, state: ConversationState): Future[SyncId]
  def postConversation(id: ConvId, users: Seq[UserId], name: Option[String]): Future[SyncId]
  def postLastRead(id: ConvId, time: Instant): Future[SyncId]
  def postCleared(id: ConvId, time: Instant): Future[SyncId]
  def postAddressBook(ab: AddressBook): Future[SyncId]
  def postInvitation(i: Invitation): Future[SyncId]
  def postTypingState(id: ConvId, typing: Boolean): Future[SyncId]
  def postExcludePymk(id: UserId): Future[SyncId]
  def postOpenGraphData(conv: ConvId, msg: MessageId): Future[SyncId]

  def registerGcm(): Future[SyncId]
  def deleteGcmToken(token: GcmId): Future[SyncId]

  def syncSelfClients(): Future[SyncId]
  def postClientLabel(id: ClientId, label: String): Future[SyncId]
  def syncClients(user: UserId): Future[SyncId]
  def syncClientsLocation(): Future[SyncId]
  def syncPreKeys(user: UserId, clients: Set[ClientId]): Future[SyncId]
  def postSessionReset(conv: ConvId, user: UserId, client: ClientId): Future[SyncId]
}

class AndroidSyncServiceHandle(context: Context, service: => SyncRequestService, timeouts: Timeouts) extends SyncServiceHandle {

  import com.waz.model.sync.SyncRequest._

  private implicit val logTag: LogTag = logTagFor[AndroidSyncServiceHandle]

  private def addRequest(req: SyncRequest, priority: Int = Priority.Normal, dependsOn: Seq[SyncId] = Nil, optional: Boolean = false, timeout: Long = 0, forceRetry: Boolean = false, delay: FiniteDuration = Duration.Zero): Future[SyncId] = {
    debug(s"addRequest: $req, prio: $priority, timeout: $timeout")
    val timestamp = SyncJob.timestamp
    val startTime = if (delay == Duration.Zero) 0 else timestamp + delay.toMillis
    service.addRequest(SyncJob(SyncId(), req, dependsOn.toSet, priority = priority, optional = optional, timeout = timeout, timestamp = timestamp, startTime = startTime), forceRetry)
  }

  def syncSearchQuery(cache: SearchQueryCache)     = addRequest(SyncSearchQuery(cache.id), priority = Priority.High)
  def syncUsers(ids: UserId*)                      = addRequest(SyncUser(ids.toSet))
  def syncSelfUser()                               = addRequest(SyncSelf, priority = Priority.High)
  def deleteAccount()                              = addRequest(DeleteAccount)
  def syncConversations(dependsOn: Option[SyncId]) = addRequest(SyncConversations, priority = Priority.High, dependsOn = dependsOn.toSeq)
  def syncConnectedUsers()                         = addRequest(SyncConnectedUsers)
  def syncConnections(dependsOn: Option[SyncId])   = addRequest(SyncConnections, dependsOn = dependsOn.toSeq)
  def syncCommonConnections(id: UserId)            = addRequest(SyncCommonConnections(id))
  def syncRichMedia(id: MessageId, priority: Int = Priority.MinPriority)  = addRequest(SyncRichMedia(id), priority = priority)
  def syncConversation(id: ConvId, dependsOn: Option[SyncId] = None)      = addRequest(SyncConversation(Set(id)), dependsOn = dependsOn.toSeq)
  def syncCallState(id: ConvId, fromFreshNotification: Boolean, priority: Int = Priority.Normal) = addRequest(SyncCallState(id, fromFreshNotification = fromFreshNotification), priority = priority)

  def postSelfUser(info: UserInfo)              = addRequest(PostSelf(info))
  def postSelfPicture(picture: Option[AssetId]) = addRequest(PostSelfPicture(picture))
  def postMessage(id: MessageId, conv: ConvId)  = addRequest(PostMessage(conv, id), timeout = System.currentTimeMillis() + ConversationsService.SendingTimeout.toMillis, forceRetry = true)
  def postDeleted(conv: ConvId, msg: MessageId) = addRequest(PostDeleted(conv, msg))
  def postAssetStatus(id: MessageId, conv: ConvId, status: AssetStatus.Syncable) = addRequest(PostAssetStatus(conv, id, status))
  def postExcludePymk(id: UserId)               = addRequest(PostExcludePymk(id), priority = Priority.Low)
  def postAddressBook(ab: AddressBook)          = addRequest(PostAddressBook(ab))
  def postInvitation(i: Invitation)             = addRequest(PostInvitation(i))
  def postConnection(user: UserId, name: String, message: String)     = addRequest(PostConnection(user, name, message))
  def postConnectionStatus(user: UserId, status: ConnectionStatus)    = addRequest(PostConnectionStatus(user, Some(status)))
  def postTypingState(conv: ConvId, typing: Boolean)                  = addRequest(PostTypingState(conv, typing), optional = true, timeout = System.currentTimeMillis() + timeouts.typing.refreshDelay.toMillis)
  def postConversationName(id: ConvId, name: String)                  = addRequest(PostConvName(id, name))
  def postConversationState(id: ConvId, state: ConversationState)     = addRequest(PostConvState(id, state))
  def postConversationMemberJoin(id: ConvId, members: Seq[UserId])    = addRequest(PostConvJoin(id, members.toSet))
  def postConversationMemberLeave(id: ConvId, member: UserId) = addRequest(PostConvLeave(id, member))
  def postConversation(id: ConvId, users: Seq[UserId], name: Option[String]) = addRequest(PostConv(id, users, name))
  def postLiking(id: ConvId, liking: Liking): Future[SyncId]          = addRequest(PostLiking(id, liking))
  def postLastRead(id: ConvId, time: Instant)         = addRequest(PostLastRead(id, time), priority = Priority.Low, delay = timeouts.messages.lastReadPostDelay)
  def postCleared(id: ConvId, time: Instant)          = addRequest(PostCleared(id, time))
  def postOpenGraphData(conv: ConvId, msg: MessageId) = addRequest(PostOpenGraphMeta(conv, msg), priority = Priority.Low)

  def registerGcm()                                 = addRequest(RegisterGcmToken, priority = Priority.Low, forceRetry = true)
  def deleteGcmToken(token: GcmId)                  = addRequest(DeleteGcmToken(token), priority = Priority.Low)

  def syncSelfClients()                             = addRequest(SyncSelfClients, priority = Priority.Critical)
  def postClientLabel(id: ClientId, label: String)  = addRequest(PostClientLabel(id, label))
  def syncClients(user: UserId)                     = addRequest(SyncClients(user))
  def syncClientsLocation()                         = addRequest(SyncClientsLocation)
  def syncPreKeys(user: UserId, clients: Set[ClientId]) = addRequest(SyncPreKeys(user, clients))

  def postSessionReset(conv: ConvId, user: UserId, client: ClientId) = addRequest(PostSessionReset(conv, user, client))
}

trait SyncHandler {
  def apply(req: SyncRequest): Future[SyncResult]
  def apply(req: SerialExecutionWithinConversation, lock: ConvLock): Future[SyncResult]
}

class AccountSyncHandler(zms: Signal[ZMessaging], otrClients: OtrClientsSyncHandler) extends SyncHandler {
  import Threading.Implicits.Background
  private implicit val logTag: LogTag = logTagFor[AccountSyncHandler]

  import com.waz.model.sync.SyncRequest._

  private def withZms(body: ZMessaging => Future[SyncResult]): Future[SyncResult] = zms.head flatMap body

  val accountHandler: PartialFunction[SyncRequest, Future[SyncResult]] = {
    case SyncSelfClients            => otrClients.syncSelfClients()
    case SyncClients(user)          => otrClients.syncClients(user)
    case SyncClientsLocation        => otrClients.syncClientsLocation()
    case SyncPreKeys(user, clients) => otrClients.syncPreKeys(Map(user -> clients.toSeq))
    case PostClientLabel(id, label) => otrClients.postLabel(id, label)

    case Unknown => Future successful SyncResult.Success
    case req: SerialExecutionWithinConversation => throw new IllegalArgumentException(s"trying to run $req without conv lock")
  }

  def zmsHandler(zms: ZMessaging): PartialFunction[SyncRequest, Future[SyncResult]] = {
    case SyncConversation(convs)  => zms.conversationSync.syncConversations(convs.toSeq)
    case SyncUser(u)              => zms.usersSync.syncUsers(u.toSeq: _*)
    case SyncSearchQuery(key)     => zms.usersearchSync.syncSearchQuery(key)
    case SyncRichMedia(messageId) => zms.richmediaSync.syncRichMedia(messageId)
    case DeleteGcmToken(token)    => zms.gcmSync.deleteGcmToken(token)

    case PostConnection(userId, name, message)  => zms.connectionsSync.postConnection(userId, name, message)
    case PostConnectionStatus(userId, status)   => zms.connectionsSync.postConnectionStatus(userId, status)

    case SyncCommonConnections(userId)  => zms.usersearchSync.syncCommonConnections(userId)
    case PostExcludePymk(userId)        => zms.usersearchSync.postExcludePymk(userId)

    case SyncCallState(convId, fresh)   => zms.voicechannelSync.syncCallState(convId, fresh)
    case SyncConversations              => zms.conversationSync.syncConversations()
    case SyncConnectedUsers             => zms.usersSync.syncConnectedUsers()
    case SyncConnections                => zms.connectionsSync.syncConnections()
    case SyncSelf                       => zms.usersSync.syncSelfUser()
    case DeleteAccount                  => zms.usersSync.deleteAccount()
    case PostSelf(info)                 => zms.usersSync.postSelfUser(info)
    case PostSelfPicture(_)             => zms.usersSync.postSelfPicture()
    case PostAddressBook(ab)            => zms.addressbookSync.postAddressBook(ab)
    case PostInvitation(i)              => zms.invitationSync.postInvitation(i)
    case RegisterGcmToken               => zms.gcmSync.registerGcm()
    case PostLiking(convId, liking)     => zms.likingsSync.postLiking(convId, liking)
    case PostDeleted(convId, msgId)     => zms.messagesSync.postDeleted(convId, msgId)
    case PostLastRead(convId, time)     => zms.lastReadSync.postLastRead(convId, time)
    case PostOpenGraphMeta(conv, msg)   => zms.openGraphSync.postMessageMeta(conv, msg)
    case PostSessionReset(conv, user, client) => zms.otrSync.postSessionReset(conv, user, client)
  }

  override def apply(req: SyncRequest): Future[SyncResult] =
    accountHandler.applyOrElse(req, { _: SyncRequest => withZms(zmsHandler(_).apply(req)) })

  override def apply(req: SerialExecutionWithinConversation, lock: ConvLock): Future[SyncResult] = withZms { zms =>
    implicit val convLock = lock

    req match {
      case PostMessage(convId, messageId)     => zms.messagesSync.postMessage(convId, messageId)
      case PostAssetStatus(cid, mid, status)  => zms.messagesSync.postAssetStatus(cid, mid, status)

      case PostConvJoin(convId, u)            => zms.conversationSync.postConversationMemberJoin(convId, u.toSeq)
      case PostConvLeave(convId, u)           => zms.conversationSync.postConversationMemberLeave(convId, u)

      case PostConv(convId, u, name)          => zms.conversationSync.postConversation(convId, u, name)
      case PostConvName(convId, name)         => zms.conversationSync.postConversationName(convId, name)
      case PostConvState(convId, state)       => zms.conversationSync.postConversationState(convId, state)
      case PostTypingState(convId, ts)        => zms.typingSync.postTypingState(convId, ts)

      case PostCleared(convId, time)          => zms.clearedSync.postCleared(convId, time)
    }
  }
}
