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
package com.waz.api.impl

import java.io.ByteArrayOutputStream

import android.graphics.Bitmap
import android.media.ExifInterface
import android.net.Uri
import android.os.Parcel
import com.google.protobuf.nano.MessageNano
import com.waz.ZLog._
import com.waz.api.ImageAsset.{BitmapCallback, SaveCallback}
import com.waz.api.LoadHandle
import com.waz.api.impl.ImageAsset.{BitmapLoadHandle, Parcelable}
import com.waz.bitmap.BitmapUtils
import com.waz.bitmap.BitmapUtils.Mime
import com.waz.model.AssetStatus.UploadDone
import com.waz.model._
import com.waz.service.ZMessaging
import com.waz.service.assets.AssetService.BitmapRequest._
import com.waz.service.assets.AssetService.BitmapResult.{BitmapLoaded, LoadingFailed}
import com.waz.service.assets.AssetService.{BitmapRequest, BitmapResult}
import com.waz.service.images.{BitmapSignal, ImageLoader}
import com.waz.threading.Threading
import com.waz.ui._
import com.waz.utils._
import com.waz.utils.events.Signal

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.ref.WeakReference
import scala.util.{Failure, Success, Try}

class ImageAsset(val id: AssetId)(implicit ui: UiModule) extends com.waz.api.ImageAsset with UiFlags with BitmapLoading with SavingToGallery with UiObservable with SignalLoading {
  private implicit val tag: LogTag = logTagFor[ImageAsset]

  var data = ImageAssetData.Empty

  protected def signal(zms: ZMessaging) =
    zms.assetsStorage.signal(id) map {
      case im: ImageAssetData => im
      case AnyAssetData(_, conv, _, _, _, _, Some(AssetPreviewData.Image(preview)), _, _, _, _) => ImageAssetData(id, conv, Seq(preview))
      case _ => ImageAssetData.Empty
    }

  addLoader(signal) { im =>
    if (this.data != im) {
      this.data = im
      notifyChanged()
    }
  }

  override def getId: String = id.str

  override def getWidth: Int = data.versions.lastOption.fold(0)(_.origWidth)

  override def getHeight: Int = data.versions.lastOption.fold(0)(_.origHeight)

  protected def getBitmap(req: BitmapRequest, callback: BitmapCallback): LoadHandle =
    BitmapLoadHandle({ zms => BitmapSignal(data, req, zms.imageLoader, zms.imageCache) }, callback)

  override def isEmpty: Boolean = false

  override def getMimeType: String = data.versions.lastOption.fold(Mime.Jpg)(_.mime)

  override def saveImageToGallery(callback: SaveCallback): Unit = ui.zms { zms =>
    zms.imageLoader.saveImageToGallery(data).onComplete(imageSaveHandler(callback))(Threading.Ui)
  }

  override def equals(other: Any): Boolean = other match {
    case other: ImageAsset => id == other.id
    case _ => false
  }

  override def hashCode: Int = id.hashCode

  override def writeToParcel(dest: Parcel, flags: Int): Unit = {
    dest.writeInt(Parcelable.FlagWire)
    dest.writeString(id.str)
  }

  override def toString = s"ImageAsset($data)"
}

class LocalImageAsset(img: ImageAssetData)(implicit ui: UiModule) extends ImageAsset(img.id) with DisableSignalLoading {
  data = img

  override def addLoader[A, B <: A](signal: (ZMessaging) => Signal[B], defaultValue: A)(onLoaded: (A) => Unit)(implicit ui: UiModule) = null

  override def getBitmap(req: BitmapRequest, callback: BitmapCallback): LoadHandle = {
    new BitmapLoadHandle( {
      case None => BitmapSignal(data, req, ui.globalImageLoader, ui.imageCache)
      case Some(zms) => BitmapSignal(data, req, zms.imageLoader, ui.imageCache)
    }, callback)
  }

  override def saveImageToGallery(callback: SaveCallback): Unit = {
    import Threading.Implicits.Background

    val loadFuture = ui.getCurrent flatMap {
      case Some(zms) => zms.imageLoader.saveImageToGallery(data)
      case None => ui.globalImageLoader.saveImageToGallery(data)
    }
    loadFuture.onComplete(imageSaveHandler(callback))(Threading.Ui)
  }

  override def writeToParcel(p: Parcel, flags: Int): Unit = {
    p.writeInt(Parcelable.FlagLocal)
    p.writeString(JsonEncoder.encodeString(img))
  }

  override def toString = s"LocalImageAsset($data)"
}

class LocalBitmapAsset(bitmap: Bitmap, orientation: Int = ExifInterface.ORIENTATION_NORMAL)(implicit ui: UiModule) extends ImageAsset(AssetId()) with DisableSignalLoading {
  private implicit val tag: LogTag = logTagFor[LocalBitmapAsset]
  import Threading.Implicits.Background
  verbose(s"creating asset from bitmap, orientation: $orientation")

  val mime = BitmapUtils.getMime(bitmap)
  val (w, h) = if (ImageLoader.Metadata.shouldSwapDimens(orientation)) (bitmap.getHeight, bitmap.getWidth) else (bitmap.getWidth, bitmap.getHeight)

  val imageData = Future {
    ui.imageCache.reserve(id, "full", bitmap.getWidth, bitmap.getHeight)
    val img = ui.bitmapDecoder.withFixedOrientation(bitmap, orientation)
    ui.imageCache.add(id, "full", img)
    verbose(s"compressing $id")
    val before = System.nanoTime
    val bos = new ByteArrayOutputStream(65536)
    val format = BitmapUtils.getCompressFormat(mime)
    img.compress(format, 85, bos)
    val bytes = bos.toByteArray
    val duration = (System.nanoTime - before) / 1e6d
    debug(s"compression took: $duration ms (${img.getWidth} x ${img.getHeight}, ${img.getByteCount} bytes -> ${bytes.length} bytes, ${img.getConfig}, $mime, $format)")
    bytes
  } (Threading.ImageDispatcher)

  data = ImageAssetData(id, RConvId(), Seq(new ImageData("full", mime, w, h, w, h, 0, Some(RAssetDataId())) {
    override lazy val data: Option[Array[Byte]] = {
      verbose(s"data requested, compress completed: ${imageData.isCompleted}")(tag)
      // XXX: this is ugly, but will only be accessed from bg thread and very rarely, so we should be fine with that hack
      LoggedTry(Await.result(imageData, 15.seconds))(tag).toOption
    }
  }))

  override def getBitmap(req: BitmapRequest, callback: BitmapCallback): LoadHandle = {
    verbose(s"get bitmap")
    new BitmapLoadHandle(_ => Signal.future(imageData) flatMap { _ => BitmapSignal(data, req, ui.globalImageLoader, ui.imageCache) }, callback)
  }

  override def saveImageToGallery(callback: SaveCallback): Unit =
    imageData.map { _ =>
      ui.globalImageLoader.saveImageToGallery(data).onComplete(imageSaveHandler(callback))(Threading.Ui)
    } .recoverWithLog()

  override def writeToParcel(p: Parcel, flags: Int): Unit = {
    p.writeInt(Parcelable.FlagLocal)
    p.writeString(JsonEncoder.encodeString(data))
  }
}

object ImageAsset {
  implicit val tag: LogTag = logTagFor[ImageAsset]

  object Parcelable {
    val FlagEmpty = 0
    val FlagLocal = 1
    val FlagWire = 2
    val FlagProto = 3
  }

  object Empty extends com.waz.api.ImageAsset with UiFlags with UiObservable {
    override def getId: String = ImageAssetData.Empty.id.str
    override def getHeight: Int = 0
    override def getWidth: Int = 0
    override def isEmpty = true
    override def getMimeType: String = null
    override def getBitmap(width: Int, callback: BitmapCallback): LoadHandle = EmptyLoadHandle
    override def getStaticBitmap(width: Int, callback: BitmapCallback): LoadHandle = EmptyLoadHandle
    override def getSingleBitmap(width: Int, callback: BitmapCallback): LoadHandle = EmptyLoadHandle
    override def getRoundBitmap(width: Int, callback: BitmapCallback): LoadHandle = EmptyLoadHandle
    override def getRoundBitmap(width: Int, borderWidth: Int, borderColor: Int, callback: BitmapCallback): LoadHandle = EmptyLoadHandle
    override def saveImageToGallery(): Unit = ()
    override def saveImageToGallery(callback: SaveCallback): Unit = { callback.imageSavingFailed(new Exception("Empty ImageAsset - can not be saved.")) }

    override def writeToParcel(dest: Parcel, flags: Int): Unit = dest.writeInt(Parcelable.FlagEmpty)
  }

  object EmptySaveCallback extends SaveCallback {
    override def imageSaved(uri: Uri): Unit = ()
    override def imageSavingFailed(ex: Exception): Unit = ()
  }

  class BitmapLoadHandle(signal: Option[ZMessaging] => Signal[BitmapResult], callback: BitmapCallback)(implicit ui: UiModule) extends LoadHandle with SignalLoading {
    private var prev = Option.empty[(WeakReference[Bitmap], Boolean, Int)]

    private var loader = Option(addLoaderOpt(signal) {
      case res @ BitmapLoaded(bitmap, preview, etag) =>
        if (prev.forall(p => !p._1.get.contains(bitmap) || p._2 != preview || p._3 != etag)) { // make sure that callback is not called twice with the same image, UI doesn't like that
          prev = Some((new WeakReference(bitmap), preview, etag))
          callback.onBitmapLoaded(bitmap, preview)
        }
      case LoadingFailed(ex) =>
        warn(s"bitmap loading failed", ex)
        callback.onBitmapLoadingFailed()
      case BitmapResult.Empty =>
        verbose(s"ignoring empty bitmap")
    })


    override def getProgressIndicator: ProgressIndicator = {
      //TODO: implement progress indicator
      ProgressIndicator.Empty
    }

    override def cancel(): Unit = {
      loader.foreach(_.destroy())
      loader = None
      prev = None
    }
  }

  object BitmapLoadHandle {
    def apply(signal: ZMessaging => Signal[BitmapResult], callback: BitmapCallback)(implicit ui: UiModule) =
      new BitmapLoadHandle(_.fold2(Signal.const(BitmapResult.Empty), signal), callback)
  }
}

trait UiFlags {
  var mirrored = false

  def setMirrored(mirrored: Boolean): Unit = this.mirrored = mirrored
}

trait BitmapLoading { self: com.waz.api.ImageAsset with UiFlags =>

  protected def getBitmap(req: BitmapRequest, callback: BitmapCallback): LoadHandle

  override def getBitmap(width: Int, callback: BitmapCallback): LoadHandle = getBitmap(Regular(width, mirror = this.mirrored), callback)

  override def getStaticBitmap(width: Int, callback: BitmapCallback): LoadHandle = getBitmap(Static(width, mirror = this.mirrored), callback)

  override def getSingleBitmap(width: Int, callback: BitmapCallback): LoadHandle = getBitmap(Single(width), callback)

  override def getRoundBitmap(width: Int, callback: BitmapCallback): LoadHandle = getBitmap(Round(width), callback)

  override def getRoundBitmap(width: Int, borderWidth: Int, borderColor: Int, callback: BitmapCallback): LoadHandle = getBitmap(Round(width, borderWidth, borderColor), callback)
}

trait SavingToGallery { self: com.waz.api.ImageAsset =>
  private implicit val tag: LogTag = logTagFor[ImageAsset]

  override def saveImageToGallery(): Unit = saveImageToGallery(new SaveCallback {
    override def imageSavingFailed(ex: Exception): Unit = ()
    override def imageSaved(uri: Uri): Unit = ()
  })

  protected def imageSaveHandler(callback: SaveCallback): (Try[Option[Uri]] => Unit) = {
    case Success(Some(uri)) => callback.imageSaved(uri)
    case Success(None) =>
      info(s"saveImageToGallery($this) failed")
      callback.imageSavingFailed(new Exception("internal error"))
    case Failure(ex) =>
      warn(s"saveImageToGallery($this) failed", ex)
      callback.imageSavingFailed(new Exception(ex))
  }
}

class ProtoImageAsset(data: GenericContent.Asset)(implicit ui: UiModule) extends com.waz.api.ImageAsset with UiFlags with BitmapLoading with SavingToGallery with UiObservable {

  override def getId = data match {
    case GenericContent.Asset(_, _, UploadDone(key)) => key.assetId.str
    case _ => data.hashCode().toHexString
  }

  lazy val dimens = data match {
    case GenericContent.Asset.WithDimensions(d) => d
    case _ => Dim2.Empty
  }

  override def getWidth = dimens.width

  override def getHeight = dimens.height

  override def isEmpty = false

  override def getMimeType = Option(data.original).fold("")(_.mimeType)

  override def writeToParcel(dest: Parcel, flags: Int) = {
    dest.writeInt(Parcelable.FlagProto)
    val arr = MessageNano.toByteArray(data)
    dest.writeInt(arr.length)
    dest.writeByteArray(arr)
  }

  override protected def getBitmap(req: BitmapRequest, callback: BitmapCallback): LoadHandle =
    BitmapLoadHandle({ zms => BitmapSignal(data, req, zms.imageLoader, zms.imageCache) }, callback)

  override def saveImageToGallery(callback: SaveCallback) =
    ui.zms { _.imageLoader.saveImageToGallery(data).onComplete(imageSaveHandler(callback))(Threading.Ui) }
}
