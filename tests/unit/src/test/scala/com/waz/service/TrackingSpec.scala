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
package com.waz.service

import java.util.Date

import android.database.sqlite.SQLiteDatabase
import com.waz._
import com.waz.api.impl.TrackingData
import com.waz.api.{KindOfCallingEvent, Message}
import com.waz.model.ConversationData.ConversationType
import com.waz.model.UserData.ConnectionStatus
import com.waz.model._
import com.waz.service.tracking.TrackingStats
import com.waz.testutils.Implicits._
import com.waz.testutils.Matchers._
import com.waz.testutils.{MockUiModule, MockZMessaging}
import com.waz.threading.Threading
import org.robolectric.Robolectric
import org.scalatest._
import org.scalatest.matchers.Matcher

import scala.concurrent.Future.sequence
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.math.min

class TrackingSpec extends FeatureSpec with Matchers with BeforeAndAfter with BeforeAndAfterAll with RobolectricTests with RobolectricUtils { test =>
  import Threading.Implicits.Background

  implicit def db: SQLiteDatabase = zmessaging.db.dbHelper.getWritableDatabase

  implicit val timeout: FiniteDuration = 2.seconds

  lazy val selfUser = UserData("self user")

  lazy val zmessaging = new MockZMessaging(selfUserId = selfUser.id)
  lazy val ui = new MockUiModule(zmessaging)

  lazy val group = 0 until 3 map { i => ConversationData(ConvId(), RConvId(), Some(s"convName $i"), selfUser.id, ConversationType.Group) }
  lazy val oneToOne = ConversationData(ConvId(), RConvId(), None, selfUser.id, ConversationType.OneToOne)
  lazy val archived: ConversationData = ConversationData(ConvId(), RConvId(), Some("archived conv"), selfUser.id, ConversationType.Group, archived = true)
  lazy val ottoConv = ConversationData(ConvId(), RConvId(), None, selfUser.id, ConversationType.OneToOne)
  lazy val otto = UserData(UserId.ofOtto, "Otto", Some(EmailAddress("does.not@matt.er")), None, None, None, 0, SearchKey("Otto"), ConnectionStatus.Accepted, new Date, None, Some(ottoConv.remoteId))
  lazy val auto1 = UserData(UserId(), "auto 1", Some(EmailAddress("n@owhe.re")), None, None, None, 0, SearchKey("auto 1"), ConnectionStatus.Accepted, new Date, None, Some(oneToOne.remoteId))
  lazy val auto2 = UserData(UserId(), "auto 2", Some(EmailAddress("no@whe.re")), None, None, None, 0, SearchKey("auto 2"), ConnectionStatus.Accepted, new Date, None, Some(RConvId()))

  lazy val tracking = new TrackingData()(ui)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    ui.onCreate(Robolectric.application)
    ui.onResume()
    sequence(
      Vector(
        zmessaging.convsStorage.insert(group ++ Seq(oneToOne, archived, ottoConv)),
        zmessaging.usersStorage.insert(Seq(auto1, otto)),
        zmessaging.messagesStorage.insert(MessageData(MessageId(), ottoConv.id, EventId(0), Message.Type.MEMBER_JOIN, selfUser.id)),
        zmessaging.messagesStorage.insert(MessageData(MessageId(), ottoConv.id, EventId(1), Message.Type.MEMBER_JOIN, otto.id)),
        zmessaging.messagesStorage.insert(MessageData(MessageId(), ottoConv.id, EventId(2), Message.Type.TEXT, otto.id)),
        zmessaging.messagesStorage.insert(MessageData(MessageId(), oneToOne.id, EventId(0), Message.Type.ASSET, selfUser.id)),
        zmessaging.callLog.add(KindOfCallingEvent.CALL_DROPPED, Option(CallSessionId()), ConvId(), false)
      ) ++ (1 to 42).map(_ => zmessaging.callLog.add(KindOfCallingEvent.CALL_ESTABLISHED, Option(CallSessionId()), ConvId(), false))
    ).await()
  }

  feature("Update tracking data") {
    scenario("Tracking data should be correctly initialized") {
      tracking should resemble(TrackingStats(4, 1, 0, 2, 0, 1, 42, 0, 0, 1, 0)) soon
    }

    scenario("Update on conversations list changes") {
      zmessaging.convsStorage.update(group.head.id, _.copy(archived = true))
      tracking should resemble(TrackingStats(4, 2, 0, 2, 0, 1, 42, 0, 0, 1, 0)) soon
    }

    scenario("Update on conversation muting") {
      zmessaging.convsStorage.update(group(1).id, _.copy(muted = true))
      tracking should resemble(TrackingStats(4, 2, 1, 2, 0, 1, 42, 0, 0, 1, 0)) soon
    }

    scenario("Update on blocking a contact (hiding a conversation)") {
      zmessaging.convsStorage.update(oneToOne.id, _.copy(hidden = true))
      tracking should resemble(TrackingStats(4, 2, 1, 1, 1, 1, 42, 0, 0, 1, 0)) soon
    }

    scenario("Additional auto-connect") {
      zmessaging.usersStorage.insert(auto2)
      tracking should resemble(TrackingStats(4, 2, 1, 1, 1, 2, 42, 0, 0, 1, 0)) soon
    }

    scenario("Send text to bot") {
      zmessaging.messagesStorage.insert(MessageData(MessageId(), ottoConv.id, EventId(3), Message.Type.TEXT, selfUser.id))
      tracking should resemble(TrackingStats(4, 2, 1, 1, 1, 2, 42, 0, 1, 1, 1)) soon
    }

    scenario("Send image somewhere") {
      zmessaging.messagesStorage.insert(MessageData(MessageId(), oneToOne.id, EventId(1), Message.Type.ASSET, selfUser.id))
      tracking should resemble(TrackingStats(4, 2, 1, 1, 1, 2, 42, 0, 1, 2, 1)) soon
    }

    scenario("Voice calls") {
      2.times(zmessaging.callLog.add(KindOfCallingEvent.CALL_ESTABLISHED, Option(CallSessionId()), ConvId(), false))
      tracking should resemble(TrackingStats(4, 2, 1, 1, 1, 2, 44, 0, 1, 2, 1)) soon
    }

    scenario("Video calls") {
      23.times(zmessaging.callLog.add(KindOfCallingEvent.CALL_ESTABLISHED, Option(CallSessionId()), ConvId(), true))
      tracking should resemble(TrackingStats(4, 2, 1, 1, 1, 2, 44, 23, 1, 2, 1)) soon
    }
  }

  def resemble(stats: TrackingStats): Matcher[TrackingData] =
    be(true).compose((_: TrackingData).isInitialized) and
    be(stats.copy(botInteractions = min(stats.botInteractions, 1))).compose((t: TrackingData) =>
      TrackingStats(t.getGroupConversationCount, t.getArchivedConversationCount, t.getMutedConversationCount,
        t.getNotBlockedContactCount, t.getBlockedContactCount, t.getAutoConnectedContactCount, t.getVoiceCallCount,
        t.getVideoCallCount, t.getSentTextMessageCount, t.getSentImagesCount, if (t.hasInteractedWithBot) 1 else 0))
}
