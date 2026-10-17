package io.github.iacoposb.adodash.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The few Material icons the design uses that are not in material-icons-core (the extended set
 * would add megabytes to an unminified APK). Paths from Material Icons (Apache 2.0).
 */
object AdoIcons {
    val AlternateEmail = icon(
        "AlternateEmail",
        "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10h5v-2h-5c-4.34,0 -8,-3.66 -8,-8s3.66,-8 8,-8 8,3.66 8,8v1.43" +
            "c0,0.79 -0.71,1.57 -1.5,1.57s-1.5,-0.78 -1.5,-1.57L17,12c0,-2.76 -2.24,-5 -5,-5s-5,2.24 -5,5 2.24,5 5,5" +
            "c1.38,0 2.64,-0.56 3.54,-1.47 0.65,0.89 1.77,1.47 2.96,1.47 1.97,0 3.5,-1.6 3.5,-3.57L22,12" +
            "c0,-5.52 -4.48,-10 -10,-10zM12,15c-1.66,0 -3,-1.34 -3,-3s1.34,-3 3,-3 3,1.34 3,3 -1.34,3 -3,3z",
    )
    val Error = icon(
        "Error",
        "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-2h2v2zM13,13h-2L11,7h2v6z",
    )
    val CheckCircle = icon(
        "CheckCircle",
        "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM10,17l-5,-5 1.41,-1.41L10,14.17l7.59,-7.59L19,8l-9,9z",
    )
    val Cancel = icon(
        "Cancel",
        "M12,2C6.47,2 2,6.47 2,12s4.47,10 10,10 10,-4.47 10,-10S17.53,2 12,2z" +
            "M17,15.59L15.59,17 12,13.41 8.41,17 7,15.59 10.59,12 7,8.41 8.41,7 12,10.59 15.59,7 17,8.41 13.41,12 17,15.59z",
    )
    val Schedule = icon(
        "Schedule",
        "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2z" +
            "M12,20c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8zM12.5,7H11v6l5.25,3.15 0.75,-1.23 -4.5,-2.67z",
    )
    val Folder = icon(
        "Folder",
        "M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z",
    )
    val Flag = icon("Flag", "M14.4,6L14,4H5v17h2v-7h5.6l0.4,2h7V6z")
    val MergeType = icon(
        "MergeType",
        "M17,20.41L18.41,19 15,15.59 13.59,17 17,20.41zM7.5,8H11v5.59L5.59,19 7,20.41l6,-6V8h3.5L12,3.5 7.5,8z",
    )
    val FilterList = icon("FilterList", "M10,18h4v-2h-4v2zM3,6v2h18V6H3zM6,13h12v-2H6v2z")
    val Hourglass = icon(
        "Hourglass",
        "M6,2v6h0.01L6,8.01 10,12l-4,4 0.01,0.01H6V22h12v-5.99h-0.01L18,16l-4,-4 4,-3.99 -0.01,-0.01H18V2H6z",
    )
    val ChatBubble = icon(
        "ChatBubble",
        "M20,2H4c-1.1,0 -2,0.9 -2,2v18l4,-4h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2z",
    )
    val CloudOff = icon(
        "CloudOff",
        "M19.35,10.04C18.67,6.59 15.64,4 12,4c-1.48,0 -2.85,0.43 -4.01,1.17l1.46,1.46C10.21,6.23 11.08,6 12,6" +
            "c3.04,0 5.5,2.46 5.5,5.5v0.5H19c1.66,0 3,1.34 3,3 0,1.13 -0.64,2.11 -1.56,2.62l1.45,1.45" +
            "C23.16,18.16 24,16.68 24,15c0,-2.64 -2.05,-4.78 -4.65,-4.96zM3,5.27l2.75,2.74C2.56,8.15 0,10.77 0,14" +
            "c0,3.31 2.69,6 6,6h11.73l2,2L21,20.73 4.27,4 3,5.27zM7.73,10l8,8H6c-2.21,0 -4,-1.79 -4,-4s1.79,-4 4,-4h1.73z",
    )
    val OpenInNew = icon(
        "OpenInNew",
        "M19,19H5V5h7V3H5c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2v-7h-2v7z" +
            "M14,3v2h3.59l-9.83,9.83 1.41,1.41L19,6.41V10h2V3h-7z",
    )
    val RateReview = icon(
        "RateReview",
        "M20,2H4c-1.1,0 -1.99,0.9 -1.99,2L2,22l4,-4h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2z" +
            "M6,14v-2.47l6.88,-6.88c0.2,-0.2 0.51,-0.2 0.71,0l1.77,1.77c0.2,0.2 0.2,0.51 0,0.71L8.47,14H6z" +
            "M18,14h-7.5l2,-2H18v2z",
    )

    private fun icon(name: String, path: String) = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(addPathNodes(path), fill = SolidColor(Color.Black)).build()
}
