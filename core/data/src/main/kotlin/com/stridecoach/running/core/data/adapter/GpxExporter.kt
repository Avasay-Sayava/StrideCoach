package com.stridecoach.running.core.data.adapter

import com.stridecoach.running.core.domain.engine.EncodedPolylineCodec
import com.stridecoach.running.core.domain.model.RunRecord
import java.time.Instant

class GpxExporter {
    fun exportRun(run: RunRecord): String {
        val points = run.trackPoints.ifEmpty {
            EncodedPolylineCodec.synthesizeReplayTrack(
                run.encodedPolyline,
                run.startTimestampMillis,
                run.durationMillis,
                run.splits
            )
        }
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"StrideCoach\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <trk>\n")
        sb.append("    <name>").append(escapeXml(run.name)).append("</name>\n")
        sb.append("    <trkseg>\n")
        for (pt in points) {
            sb.append("      <trkpt lat=\"").append(pt.latitude).append("\" lon=\"").append(pt.longitude).append("\">\n")
            if (pt.altitudeMeters != null) {
                sb.append("        <ele>").append(pt.altitudeMeters).append("</ele>\n")
            }
            sb.append("        <time>").append(Instant.ofEpochMilli(pt.timestampMillis)).append("</time>\n")
            sb.append("      </trkpt>\n")
        }
        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")
        return sb.toString()
    }

    private fun escapeXml(input: String): String = input
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
