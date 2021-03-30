package com.francescotaurone.opencamerastudio.studio

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.support.v4.content.LocalBroadcastManager
import com.francescotaurone.opencamerastudio.MainActivity
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import android.webkit.MimeTypeMap
import java.io.IOException
import com.github.hiteshsondhi88.libffmpeg.exceptions.FFmpegNotSupportedException
import com.github.hiteshsondhi88.libffmpeg.LoadBinaryResponseHandler
//import sun.awt.FontConfiguration.loadBinary
import com.github.hiteshsondhi88.libffmpeg.FFmpeg
import com.github.hiteshsondhi88.libffmpeg.exceptions.FFmpegCommandAlreadyRunningException
import com.github.hiteshsondhi88.libffmpeg.ExecuteBinaryResponseHandler
import kotlinx.coroutines.*

class StudioServer(val mainActivity: MainActivity, val port : Int) : NanoHTTPD(port) {
    override fun serve(session: IHTTPSession): Response {
        val path = session.uri?.substring(1) ?: ""
        when (path) {
            "list" -> return listFiles()
            "download" -> {
                val filename = session.parameters["file"]
                if (filename != null) {
                    return downloadVideo(filename[0])
                }else{
                    return getNotFoundResponse()
                }
            }
            "partialDownload" -> {
                val ffmpeg = FFmpeg.getInstance(mainActivity)
                try {
                    ffmpeg.loadBinary(object : LoadBinaryResponseHandler() {

                        override fun onStart() {}

                        override fun onFailure() {}

                        override fun onSuccess() {}

                        override fun onFinish() {}
                    })
                } catch (e: FFmpegNotSupportedException) {
                    // Handle if FFmpeg is not supported by device
                }

                val filename = session.parameters["file"]
                val listStartFrom = session.parameters["start"]

                if ((listStartFrom != null) && (filename != null)) {

                    val startFrom = listStartFrom[0].toString()
                    val retriever = MediaMetadataRetriever();

                    retriever.setDataSource(mainActivity, Uri.fromFile(File("/storage/emulated/0/DCIM/OpenCamera/" + filename[0].toString())))
                    val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    retriever.release()
                    val videoTimeInMillisec = java.lang.Long.parseLong(time)
                    var requestedTimeInMillisec = -java.lang.Long.parseLong(startFrom) * 1000
                    if (requestedTimeInMillisec < 0){
                        requestedTimeInMillisec = -requestedTimeInMillisec
                    }
                    if (requestedTimeInMillisec > videoTimeInMillisec){
                        return downloadVideo(filename[0])
                    }



                    //val f = File("/storage/emulated/0/DCIM/OpenCamera/" + filename[0].toString())
                    try {
                        // to execute "ffmpeg -version" command you just need to pass "-version"
                        //val cmd = arrayOf("-y", "-ss", "00:00:0"+ startFrom, "-i", "/storage/emulated/0/DCIM/OpenCamera/" + filename[0].toString(), "-to", "00:00:0"+ end, "-async" , "1", "-strict",  "-2",  "-c copy",  "/storage/emulated/0/DCIM/OpenCamera/TRIM" + filename[0].toString())
                        val cmd = arrayOf("-sseof", startFrom,"-i", "/storage/emulated/0/DCIM/OpenCamera/" + filename[0].toString(),"-y", "-c" ,"copy", "/storage/emulated/0/DCIM/OpenCamera/TRIM" + filename[0].toString())
                        var finishedFFmpeg = false
                        ffmpeg.execute(cmd, object : ExecuteBinaryResponseHandler() {

                            override fun onStart() {Log.e("gc", "Command Started");}

                            override fun onProgress(message: String?) {Log.e("gc", "onProgress" + message);}

                            override fun onFailure(message: String?) {Log.e("gc", "onFailure command" + message)}

                            override fun onSuccess(message: String?) {Log.e("gc", "onSuccess command" + message);}

                            override fun onFinish() {Log.e("gc", "onFinish command");finishedFFmpeg = true}
                        })
                        var counter = 0
                        while(!finishedFFmpeg && counter < 10000) {
                            print("Waiting for ffmpeg to finish")
                        }
                        if (counter >= 10000){
                            Log.e("timeout", "ffmpeg timeout, proceding")
                            return getNotFoundResponse()
                        }
                        return downloadVideo("TRIM" + filename[0])
                    } catch (e: FFmpegCommandAlreadyRunningException) {
                        // Handle if FFmpeg is already running
                    }

                    /*
                    var endAt: Long = -1
                    val fileLen = f.length()
                    endAt = fileLen
                    var newLen = endAt - startFrom
                    if (newLen < 0) {
                        newLen = 0
                    }

                    val fileLen = f.length()
                    var endAt: Long = end
                    var newLen = end-startFrom
                    fis.skip(startFrom)
                    val map = MimeTypeMap.getSingleton();
                    val mime = map.getMimeTypeFromExtension(f.extension).toString()
                    val etag = Integer.toHexString((f.absolutePath
                            + f.lastModified() + "" + f.length()).hashCode())
                    var res: NanoHTTPD.Response
                    res = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.PARTIAL_CONTENT, mime, fis, newLen)
                    res = NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.PARTIAL_CONTENT, mime, fis)
                    */
                    //res.addHeader("Accept-Ranges", "bytes")
                    //res.addHeader("Content-Length", "" + newLen)
                    //res.addHeader("Content-Range", "bytes " + startFrom + "-" + endAt + "/*")
                    //res.addHeader("ETag", etag)
                    //res.addHeader("Content-Disposition", "attachment; filename=\"" + f.name + "\"")
                    //res.addHeader("Access-Control-Allow-Origin", "*")
                    //return res

                }
                else {
                    return getNotFoundResponse()
                }

            }
            "isRecording" ->{
                return newFixedLengthResponse(mainActivity.preview.isVideoRecording().toString())
            }
            "stream" -> {
                val filename = session.parameters["file"]
                if (filename != null) {
                    return downloadVideo(filename[0], forceDownload = false)
                }else{
                    return getNotFoundResponse()
                }
            }
            "delete" -> {
                val filename = session.parameters["file"]
                if (filename != null) {
                    return deleteVideo(filename[0])
                }else{
                    return getNotFoundResponse()
                }
            }
            "start" -> {
                val name = session.parameters["name"]?.get(0) ?: ""
                val opt = JSONObject()
                opt.put("name", name)
                sendCommand("start", opt)
                return newFixedLengthResponse("OK")
            }
            "stop" -> {
                sendCommand("stop", null)
                return newFixedLengthResponse("OK")
            }
            "checkocs" -> {
                return newFixedLengthResponse("OK")
            }
        }

        val cacheDir = mainActivity.cacheDir
        val websiteCacheDir = File(cacheDir, "website")
        return newFixedFileResponse(File(websiteCacheDir, "index.html"), "text/html")
    }

    fun listFiles(): Response {
        /*val storageDir = File(mainActivity.storageUtils.saveLocation)*/
        val storageDir = File("/storage/emulated/0/DCIM/OpenCamera/")
            val resArray = JSONArray()

            storageDir.listFiles().forEach { file ->
                val obj = JSONObject()
                obj.put("name", file.name)
                obj.put("size", file.length())
                obj.put("lastModified", file.lastModified())
                resArray.put(obj)
        }

        val res = resArray.toString()+"\n"
        return newFixedLengthResponse(res)
    }

    fun downloadVideo(name: String, forceDownload: Boolean = true): Response {
        /*val storageDir = File(mainActivity.storageUtils.saveLocation)*/
        val storageDir = File("/storage/emulated/0/DCIM/OpenCamera/")

        val map = MimeTypeMap.getSingleton();
        val mime = map.getMimeTypeFromExtension(File(name).extension).toString()
        return serveFile(mapOf(), File(storageDir, name), mime, forceDownload)
    }


    fun deleteVideo(name: String): Response {
        val storageDirOriginal = File(mainActivity.storageUtils.saveLocation)
        val storageDir = File("/storage/emulated/0/DCIM/OpenCamera/")
        val targetFile = File(storageDir, name)
        val res = targetFile.delete()
        return if (res) {
            newFixedLengthResponse("OK")
        }else{
            newFixedLengthResponse("Error")
        }
    }

    fun sendCommand(type: String, opt: JSONObject?) {
        val intent = Intent(MainActivity.STUDIO_BROADCAST_ID)
        val obj = JSONObject()
        obj.put("type", type)
        if (opt != null) {
            obj.put("opt", opt)
        }
        intent.putExtra("data", obj.toString());
        LocalBroadcastManager.getInstance(mainActivity).sendBroadcast(intent)
    }

    /**
     * Serves file from homeDir and its' subdirectories (only). Uses only URI,
     * ignores all headers and HTTP parameters.
     */
    //private Response serveFile(String uri, Map<String, String> header, DocumentFile file, String mime) {
    private fun serveFile(header: Map<String, String>, file: File, mime: String, forceDownload: Boolean): NanoHTTPD.Response {
        var res: NanoHTTPD.Response
        try {
            // Calculate etag
            //String etag = Integer.toHexString((file.getAbsolutePath() + file.lastModified() + "" + file.length()).hashCode());

            // Support (simple) skipping:
            var startFrom: Long = 0
            var endAt: Long = -1
            var range = header["range"]
            if (range != null) {
                if (range.startsWith("bytes=")) {
                    range = range.substring("bytes=".length)
                    val minus = range.indexOf('-')
                    try {
                        if (minus > 0) {
                            startFrom = java.lang.Long.parseLong(range.substring(0, minus))
                            endAt = java.lang.Long.parseLong(range.substring(minus + 1))
                        }
                    } catch (ignored: NumberFormatException) {
                    }

                }
            }

            // get if-range header. If present, it must match etag or else we
            // should ignore the range request
            val ifRange = header["if-range"]
            //boolean headerIfRangeMissingOrMatching = (ifRange == null || etag.equals(ifRange));
            val headerIfRangeMissingOrMatching = ifRange == null

            val ifNoneMatch = header["if-none-match"]
            //boolean headerIfNoneMatchPresentAndMatching = ifNoneMatch != null && ("*".equals(ifNoneMatch) || ifNoneMatch.equals(etag));
            val headerIfNoneMatchPresentAndMatching = ifNoneMatch != null && "*" == ifNoneMatch

            // Change return code and add Content-Range header when skipping is
            // requested
            val fileLen = file.length()

            if (headerIfRangeMissingOrMatching && range != null && startFrom >= 0 && startFrom < fileLen) {
                // range request that matches current etag
                // and the startFrom of the range is satisfiable
                if (headerIfNoneMatchPresentAndMatching) {
                    // range request that matches current etag
                    // and the startFrom of the range is satisfiable
                    // would return range from file
                    // respond with not-modified
                    res = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_MODIFIED, mime, "")
                    //res.addHeader("ETag", etag);
                } else {
                    if (endAt < 0) {
                        endAt = fileLen - 1
                    }
                    var newLen = endAt - startFrom + 1
                    if (newLen < 0) {
                        newLen = 0
                    }

                    val fis = file.inputStream()

                    fis.skip(startFrom)

                    res = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.PARTIAL_CONTENT, mime, fis, newLen)
                    res.addHeader("Accept-Ranges", "bytes")
                    res.addHeader("Content-Length", "" + newLen)
                    res.addHeader("Content-Range", "bytes $startFrom-$endAt/$fileLen")
                    //res.addHeader("ETag", etag);
                }
            } else {

                if (headerIfRangeMissingOrMatching && range != null && startFrom >= fileLen) {
                    // return the size of the file
                    // 4xx responses are not trumped by if-none-match
                    res = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.RANGE_NOT_SATISFIABLE, NanoHTTPD.MIME_PLAINTEXT, "")
                    res.addHeader("Content-Range", "bytes */$fileLen")
                    //res.addHeader("ETag", etag);
                } else if (range == null && headerIfNoneMatchPresentAndMatching) {
                    // full-file-fetch request
                    // would return entire file
                    // respond with not-modified
                    res = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_MODIFIED, mime, "")
                    //res.addHeader("ETag", etag);
                } else if (!headerIfRangeMissingOrMatching && headerIfNoneMatchPresentAndMatching) {
                    // range request that doesn't match current etag
                    // would return entire (different) file
                    // respond with not-modified

                    res = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_MODIFIED, mime, "")
                    //res.addHeader("ETag", etag);
                } else {
                    // supply the file
                    res = newFixedFileResponse(file, mime)
                    res.addHeader("Content-Length", "" + fileLen)
                    //res.addHeader("ETag", etag);
                }
            }
        } catch (ioe: IOException) {
            res = getForbiddenResponse("Reading file failed.")
        }

        if (!forceDownload) {
            res.addHeader("Content-Disposition", "inline; filename=\"" + file.name + "\"")
        } else {
            res.addHeader("Content-Disposition", "attachment; filename=\"" + file.name + "\"")
        }
        res.addHeader("Access-Control-Allow-Origin", "*")
        return res
    }

    private fun newFixedFileResponse(file: File, mime: String): NanoHTTPD.Response {
        val inputStream = file.inputStream()
        val res: NanoHTTPD.Response
        res = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, mime, inputStream, file.length())
        res.addHeader("Accept-Ranges", "bytes")
        return res
    }

    private fun getNotFoundResponse(): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Error 404")
    }

    private fun getErrorResponse(): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "Internal Error")
    }

    private fun getForbiddenResponse(s: String): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.FORBIDDEN, NanoHTTPD.MIME_PLAINTEXT, "FORBIDDEN: $s")
    }
}