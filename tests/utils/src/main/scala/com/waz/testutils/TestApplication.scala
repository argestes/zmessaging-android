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
package com.waz.testutils

import android.app.Application
import com.waz.api.NotificationsHandler.{ActiveChannel, NotificationsHandlerFactory}
import com.waz.api._
import com.waz.service.ZMessaging

class TestApplication extends Application with NotificationsHandlerFactory {
  override def getNotificationsHandler: NotificationsHandler = TestApplication.notificationsHandler
  override def getCallingEventsHandler: CallingEventsHandler = TestApplication.callingEventsHandler
  override def getTrackingEventsHandler: TrackingEventsHandler = ZMessaging.EmptyTrackingEventsHandler
}

object TestApplication {
  val callingEventsSpy = new CallingEventsSpy(Nil)
  val notificationsSpy = new NotificationsSpy(Seq.empty, None, None, true)

  private val notificationsHandler: NotificationsHandler = notificationsSpy
  private val callingEventsHandler: CallingEventsHandler = callingEventsSpy
}

class CallingEventsSpy(var events: List[CallingEvent]) extends CallingEventsHandler {
  // gets all its updates on the UI thread
  override def onCallingEvent(event: CallingEvent): Unit = events = event :: events

  def latestEvent: Option[CallingEvent] = events.headOption
}

class NotificationsSpy(var gcms: Seq[GcmNotificationsList], var ongoingCall: Option[ActiveChannel], var incomingCall: Option[ActiveChannel], var uiActive: Boolean) extends NotificationsHandler {
  override def updateGcmNotification(notifications: GcmNotificationsList): Unit = gcms :+= notifications
  override def updateOngoingCallNotification(ongoingCall: ActiveChannel, incomingCall: ActiveChannel, isUiActive: Boolean): Unit = {
    this.ongoingCall = Option(ongoingCall)
    this.incomingCall = Option(incomingCall)
    uiActive = isUiActive
  }
}
