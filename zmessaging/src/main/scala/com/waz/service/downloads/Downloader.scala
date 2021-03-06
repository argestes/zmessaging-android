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
package com.waz.service.downloads

import java.io._
import java.security.{DigestOutputStream, MessageDigest}
import java.util.concurrent.atomic.AtomicBoolean

import android.content.Context
import com.waz.HockeyApp
import com.waz.ZLog._
import com.waz.api.impl.ProgressIndicator._
import com.waz.api.impl.{ErrorResponse, ProgressIndicator}
import com.waz.bitmap.video.VideoTranscoder
import com.waz.cache.{CacheEntry, CacheService}
import com.waz.content.Mime
import com.waz.model._
import com.waz.service.TempFileService
import com.waz.service.assets.{AssetLoader, AudioTranscoder}
import com.waz.service.downloads.DownloadRequest._
import com.waz.sync.client.AssetClient
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.utils._
import com.waz.utils.events.EventStream
import com.waz.znet.Response.ResponseBodyDecoder
import com.waz.znet.ResponseConsumer.FileConsumer
import com.waz.znet.{FileResponse, Request}

import scala.concurrent._
import scala.util.Try


sealed trait Downloader[-A <: DownloadRequest] {
  def load(request: A, callback: ProgressIndicator.Callback): CancellableFuture[Option[CacheEntry]]
}

object Downloader {
  def empty[A <: DownloadRequest] = new Downloader[A] {
    override def load(request: A, callback: Callback): CancellableFuture[Option[CacheEntry]] = CancellableFuture successful None
  }
}

class AssetDownloader(client: AssetClient, cache: CacheService) extends Downloader[AssetRequest] {
  import com.waz.threading.Threading.Implicits.Background
  private implicit val tag = logTagFor[AssetDownloader]

  val onDownloadStarting = EventStream[AssetRequest]()
  val onDownloadDone     = EventStream[AssetRequest]()
  val onDownloadFailed   = EventStream[(AssetRequest, ErrorResponse)]()

  private def httpRequest(asset: AssetRequest, callback: Callback): Option[Request[Unit]] = asset match {
    case wa: WireAssetRequest =>
      val path = AssetClient.getAssetPath(wa.convId, wa.key)
      val headers = wa.key.token.fold(Map.empty[String, String])(t => Map("Asset-Token" -> t.str))
      val decoder = new AssetBodyDecoder(cache, Option(wa.key.otrKey).filter(_ != AESKey.Empty), Option(wa.key.sha256).filter(_ != Sha256.Empty))
      Some(new Request[Unit](Request.GetMethod, Some(path), downloadCallback = Some(callback), decoder = Some(decoder), headers = headers))
    case req: ExternalAssetRequest =>
      Some(req.request.copy(downloadCallback = Some(callback), decoder = Some(new AssetBodyDecoder(cache))))
    case LocalAssetRequest(_, _, _, _) => None
    case CachedAssetRequest(_, _, _) => None
  }

  override def load(asset: AssetRequest, callback: Callback): CancellableFuture[Option[CacheEntry]] =
    httpRequest(asset, callback) match {
      case None => CancellableFuture successful None
      case Some(req) =>
        onDownloadStarting ! asset
        client.loadAsset(req) flatMap {
          case Right(entry) =>
            CancellableFuture lift {
              cache.move(asset.cacheKey, entry, asset.mime, asset.name) map { res =>
                onDownloadDone ! asset
                Some(res)
              }
            }
          case Left(err) =>
            error(s"loadAsset failed: $err")
            onDownloadFailed ! (asset, err)
            CancellableFuture successful None
        }
    }
}

class AssetBodyDecoder(cache: CacheService, key: Option[AESKey] = None, sha: Option[Sha256] = None) extends ResponseBodyDecoder {
  override def apply(contentType: String, contentLength: Long) = new AssetDataConsumer(contentType, cache, key, sha)
}

/**
  * Consumes data for downloaded assets. Will always write directly to cache file.
  * Encrypted assets are not decrypted, key is passed to cache entry and will be used later when asset is loaded from cache.
  * Sha is computed on the fly and download fails if it doesn't match.
  */
class AssetDataConsumer(mime: String, cache: CacheService, key: Option[AESKey], sha: Option[Sha256]) extends FileConsumer(mime)(cache) {
  override lazy val entry: CacheEntry = cache.createManagedFile(key)
  val shaStream = new DigestOutputStream(new BufferedOutputStream(new FileOutputStream(entry.cacheFile)), MessageDigest.getInstance("SHA-256"))
  override lazy val out = shaStream

  override def result: Try[FileResponse] =
    super.result filter { _ =>
      sha.forall(_ == Sha256(shaStream.getMessageDigest.digest()))
    }
}

class InputStreamAssetLoader(cache: => CacheService) extends Downloader[AssetFromInputStream] {
  def load(asset: AssetFromInputStream, callback: ProgressIndicator.Callback): CancellableFuture[Option[CacheEntry]] =
    InputStreamAssetLoader.addStreamToCache(cache, asset.cacheKey, asset.stream(), asset.mime, asset.name)
}

object InputStreamAssetLoader {
  import Threading.Implicits.Background

  def addStreamToCache(cache: CacheService, key: String, stream: => InputStream, mime: Mime, name: Option[String]) = {
    val promise = Promise[Option[CacheEntry]]
    val cancelled = new AtomicBoolean(false)

    // important: this file needs to be stored unencrypted so we can process it (for e.g. video thumbnails); specifying an explicit cache location "forces" that (for now)
    promise.tryCompleteWith {
      cache.addStream(key, new CancellableStream(stream, cancelled), mime, name, cacheLocation = Some(cache.cacheDir), execution = Threading.BlockingIO)
        .map(Some(_))
        .recover {
          case e: Throwable =>
            warn(s"addStreamToCache failed", e)("InputStreamAssetLoader")
            None
        }
    }

    new CancellableFuture(promise) {
      override def cancel()(implicit tag: LogTag): Boolean = cancelled.compareAndSet(false, true)
    }
  }
}

class VideoAssetLoader(context: Context, cache: => CacheService) extends Downloader[VideoAsset] {
  private implicit val tag: LogTag = logTagFor[VideoAssetLoader]

  override def load(request: VideoAsset, callback: Callback): CancellableFuture[Option[CacheEntry]] = {
    import Threading.Implicits.Background

    // TODO: check input type, size, bitrate, maybe we don't need to transcode it
    val entry = cache.createManagedFile()

    VideoTranscoder(context).apply(request.uri, entry.cacheFile, callback) flatMap { _ =>
      verbose(s"loaded video from $request, resulting file size: ${entry.length}")
      CancellableFuture lift cache.move(request.cacheKey, entry, Mime.Video.MP4, if (request.mime == Mime.Video.MP4) request.name else request.name.map(_ + ".mp4"), cacheLocation = Some(cache.cacheDir)) map (Some(_))
    } recoverWith {
      case ex: Exception =>
        HockeyApp.saveException(ex, s"video transcoding failed for uri: ${request.uri}")
        InputStreamAssetLoader.addStreamToCache(cache, request.cacheKey, AssetLoader.openStream(context, request.uri), request.mime, request.name)
    }
  }
}

class UnencodedAudioAssetLoader(context: Context, cache: => CacheService, tempFiles: TempFileService) extends Downloader[UnencodedAudioAsset] {
  import Threading.Implicits.Background

  private implicit def tag: LogTag = logTagFor[UnencodedAudioAssetLoader]
  private val transcode = new AudioTranscoder(tempFiles, context)

  override def load(request: UnencodedAudioAsset, callback: Callback): CancellableFuture[Option[CacheEntry]] = {
    val entry = cache.createManagedFile()

    returning(transcode(request.uri, entry.cacheFile, callback).flatMap { _ =>
      verbose(s"loaded audio from $request, resulting file size: ${entry.length}")
      cache.move(request.cacheKey, entry, Mime.Audio.MP4, request.name, cacheLocation = Some(cache.cacheDir)).map(Some(_)).lift
    })(_.onFailure {
      case cause: CancellableFuture.CancelException => verbose(s"audio encoding cancelled: ${request.uri}")
      case cause: Throwable => HockeyApp.saveException(cause, s"audio encoding failed for URI: ${request.uri}")
    })
  }
}

